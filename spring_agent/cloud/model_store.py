"""云端模型目录：MySQL 为权威数据，Redis 快照缓存。"""

from __future__ import annotations

from typing import Any

import redis
from sqlalchemy import select

from spring_agent.cloud.cache_crypto import (
    COMPARE_DELETE_SCRIPT,
    CacheSecretCipher,
    decode_envelope,
    encode_envelope,
)
from spring_agent.cloud.db import (
    ModelDefinitionRow,
    ModelProviderRow,
    SystemSettingRow,
    get_sessionmaker,
)
from spring_agent.core.config.settings import Model as ModelConfig
from spring_agent.core.config.settings import Provider as ProviderConfig
from spring_agent.core.log import logger

# system_settings 里默认模型的键
KEY_DEFAULT_MODEL = "default_model"

# 子代理模型设置键前缀，完整键为 subagents.<名字>
KEY_SUBAGENT_PREFIX = "subagents."

# 缓存 key 与 TTL：TTL 是最终一致性兜底（延迟双删失败/遗漏时最多脏 60s）
CACHE_KEY = "modelconf:catalog"
CACHE_TTL_SECONDS = 60


def _split_csv(csv: str | None) -> list[str]:
    if not csv:
        return []
    return [item.strip() for item in csv.split(",") if item.strip()]


def _model_config_from_dict(d: dict[str, Any]) -> ModelConfig:
    return ModelConfig(
        provider=d["provider"],
        model=d["model"],
        max_context_size=d["max_context_size"],
        display_name=d["display_name"],
        max_output_size=d["max_output_size"],
        capabilities=_split_csv(d["capabilities"]),
        support_efforts=_split_csv(d["support_efforts"]),
        default_effort=d["default_effort"],
    )


def load_model_fresh(model_id: str) -> tuple[ModelConfig, ProviderConfig] | None:
    """直读 MySQL 取模型及其 Provider，绕过 Redis 快照。"""
    with get_sessionmaker()() as s:
        model_row = s.get(ModelDefinitionRow, model_id)
        if model_row is None:
            return None
        provider_row = s.get(ModelProviderRow, model_row.provider)
        if provider_row is None:
            return None
        return (
            _model_config_from_dict({
                "provider": model_row.provider,
                "model": model_row.model,
                "display_name": model_row.display_name,
                "max_context_size": model_row.max_context_size,
                "max_output_size": model_row.max_output_size,
                "capabilities": model_row.capabilities,
                "support_efforts": model_row.support_efforts,
                "default_effort": model_row.default_effort,
            }),
            ProviderConfig(
                type=provider_row.type,
                api_key=provider_row.api_key,
                base_url=provider_row.base_url,
            ),
        )


def _validate_snapshot(snapshot: dict[str, Any]) -> None:
    if set(snapshot) != {"default_model", "providers", "models", "subagents"}:
        raise ValueError("模型目录字段无效")
    if not isinstance(snapshot["default_model"], str):
        raise ValueError("默认模型格式无效")
    for field in ("providers", "models", "subagents"):
        if not isinstance(snapshot[field], dict) or not all(
            isinstance(key, str) for key in snapshot[field]
        ):
            raise ValueError("模型目录格式无效")
    for provider in snapshot["providers"].values():
        if (
            not isinstance(provider, dict)
            or set(provider) != {"type", "api_key", "base_url"}
            or not isinstance(provider["type"], str)
            or not isinstance(provider["api_key"], str)
            or not (provider["base_url"] is None or isinstance(provider["base_url"], str))
        ):
            raise ValueError("模型厂商格式无效")
    text_fields = {
        "provider", "model", "display_name", "capabilities", "support_efforts", "default_effort",
    }
    number_fields = {"max_context_size", "max_output_size"}
    for model in snapshot["models"].values():
        if (
            not isinstance(model, dict)
            or set(model) != text_fields | number_fields | {"enabled"}
            or not all(isinstance(model[field], str) for field in text_fields)
            or not all(type(model[field]) is int for field in number_fields)
            or type(model["enabled"]) is not bool
            or model["provider"] not in snapshot["providers"]
        ):
            raise ValueError("模型定义格式无效")
    if not all(isinstance(value, str) for value in snapshot["subagents"].values()):
        raise ValueError("子代理模型格式无效")


class DbCatalog:
    """模型配置目录的加密快照缓存。

    @author hanbing
    @since 2026-10-17
    """

    def __init__(self, cache: redis.Redis | None, cipher: CacheSecretCipher) -> None:
        self._cache = cache
        self._cipher = cipher

    def default_model(self) -> str:
        return self._snapshot()["default_model"]

    def get_model_config(self, model_id: str) -> ModelConfig | None:
        entry = self._snapshot()["models"].get(model_id)
        return _model_config_from_dict(entry) if entry is not None else None

    def get_provider_config(self, provider_name: str) -> ProviderConfig | None:
        entry = self._snapshot()["providers"].get(provider_name)
        if entry is None:
            return None
        return ProviderConfig(
            type=entry["type"], api_key=entry["api_key"], base_url=entry["base_url"],
        )

    def list_model_configs(self) -> list[tuple[str, ModelConfig]]:
        models = self._snapshot()["models"]
        return [
            (mid, _model_config_from_dict(entry))
            for mid, entry in sorted(models.items())
            if entry["enabled"]
        ]

    def subagent_model(self, name: str) -> str:
        return self._snapshot()["subagents"].get(name, "")

    def _snapshot(self) -> dict[str, Any]:
        cached = self._load_from_cache()
        if cached is not None:
            return cached
        snapshot = self._load_from_db()
        if self._cache is not None:
            try:
                _validate_snapshot(snapshot)
                payload = self._transform_keys(snapshot, encrypt=True)
                self._cache.set(CACHE_KEY, encode_envelope(payload), ex=CACHE_TTL_SECONDS)
            except Exception:
                logger.warning("模型目录缓存回填失败")
        return snapshot

    def _transform_keys(self, snapshot: dict[str, Any], *, encrypt: bool) -> dict[str, Any]:
        transform = self._cipher.encrypt if encrypt else self._cipher.decrypt
        return {
            **snapshot,
            "providers": {
                name: {
                    **provider,
                    "api_key": transform(provider["api_key"], CACHE_KEY, "providers", name, "api_key"),
                }
                for name, provider in snapshot["providers"].items()
            },
        }

    def _load_from_cache(self) -> dict[str, Any] | None:
        if self._cache is None:
            return None
        try:
            raw = self._cache.get(CACHE_KEY)
        except Exception:
            logger.warning("模型目录缓存读取失败，回源数据库")
            return None
        if raw is None:
            return None
        try:
            snapshot = decode_envelope(raw)
            _validate_snapshot(snapshot)
            return self._transform_keys(snapshot, encrypt=False)
        except Exception:
            logger.warning("模型目录缓存无效，回源数据库")
        try:
            self._cache.eval(COMPARE_DELETE_SCRIPT, 1, CACHE_KEY, raw)
        except Exception:
            logger.warning("模型目录缓存清除失败")
        return None

    def _load_from_db(self) -> dict[str, Any]:
        with get_sessionmaker()() as s:
            providers = {
                row.name: {"type": row.type, "api_key": row.api_key, "base_url": row.base_url}
                for row in s.scalars(select(ModelProviderRow)).all()
            }
            models = {
                row.id: {
                    "provider": row.provider,
                    "model": row.model,
                    "display_name": row.display_name,
                    "max_context_size": row.max_context_size,
                    "max_output_size": row.max_output_size,
                    "capabilities": row.capabilities,
                    "support_efforts": row.support_efforts,
                    "default_effort": row.default_effort,
                    "enabled": row.enabled,
                }
                for row in s.scalars(select(ModelDefinitionRow)).all()
            }
            default_row = s.get(SystemSettingRow, KEY_DEFAULT_MODEL)
            subagents = {
                row.setting_key[len(KEY_SUBAGENT_PREFIX):]: row.setting_value
                for row in s.scalars(select(SystemSettingRow)).all()
                if row.setting_key.startswith(KEY_SUBAGENT_PREFIX)
            }
            return {
                "default_model": default_row.setting_value if default_row else "",
                "providers": providers,
                "models": models,
                "subagents": subagents,
            }
