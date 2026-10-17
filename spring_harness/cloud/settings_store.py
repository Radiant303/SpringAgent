"""平台设置的数据源与加密快照缓存。"""

from __future__ import annotations

import redis
from sqlalchemy import select

from spring_harness.cloud.cache_crypto import (
    COMPARE_DELETE_SCRIPT,
    CacheSecretCipher,
    decode_envelope,
    encode_envelope,
)
from spring_harness.cloud.db import SystemSettingRow, get_sessionmaker
from spring_harness.core.config.settings_policy import (
    CACHEABLE_KEYS,
    SENSITIVE_KEYS,
    require_cacheable_key,
)
from spring_harness.core.log import logger

CACHE_KEY = "sysconf:settings"
# 设置快照的缓存有效期（秒）。
CACHE_TTL_SECONDS = 60


class DbSettings:
    """平台设置读取器。

    @author hanbing
    @since 2026-10-17
    """

    def __init__(self, cache: redis.Redis | None, cipher: CacheSecretCipher) -> None:
        self._cache = cache
        self._cipher = cipher

    def get(self, key: str, default: str = "") -> str:
        require_cacheable_key(key)
        return self.snapshot().get(key, default)

    @staticmethod
    def _validate(snapshot: dict) -> None:
        if not all(
            key in CACHEABLE_KEYS and isinstance(value, str)
            for key, value in snapshot.items()
        ):
            raise ValueError("设置快照格式无效")

    def snapshot(self) -> dict[str, str]:
        cached = self._load_from_cache()
        if cached is not None:
            return cached
        snapshot = self._load_from_db()
        if self._cache is not None:
            try:
                self._validate(snapshot)
                payload = {
                    key: self._cipher.encrypt(value, CACHE_KEY, key)
                    if key in SENSITIVE_KEYS else value
                    for key, value in snapshot.items()
                }
                self._cache.set(CACHE_KEY, encode_envelope(payload), ex=CACHE_TTL_SECONDS)
            except Exception:
                logger.warning("设置快照缓存回填失败")
        return snapshot

    def _load_from_cache(self) -> dict[str, str] | None:
        if self._cache is None:
            return None
        try:
            raw = self._cache.get(CACHE_KEY)
        except Exception:
            logger.warning("设置快照缓存读取失败，回源数据库")
            return None
        if raw is None:
            return None
        try:
            snapshot = decode_envelope(raw)
            self._validate(snapshot)
            return {
                key: self._cipher.decrypt(value, CACHE_KEY, key)
                if key in SENSITIVE_KEYS else value
                for key, value in snapshot.items()
            }
        except Exception:
            logger.warning("设置快照缓存无效，回源数据库")
        try:
            self._cache.eval(COMPARE_DELETE_SCRIPT, 1, CACHE_KEY, raw)
        except Exception:
            logger.warning("设置快照缓存清除失败")
        return None

    def _load_from_db(self) -> dict[str, str]:
        with get_sessionmaker()() as s:
            rows = s.execute(
                select(SystemSettingRow.setting_key, SystemSettingRow.setting_value).where(
                    SystemSettingRow.setting_key.in_(CACHEABLE_KEYS)
                )
            )
            return {key: value for key, value in rows}
