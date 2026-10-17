"""system_settings：MySQL 为权威数据，Redis 快照缓存（读多写少的热点数据）。"""

from __future__ import annotations

import json

import redis
from sqlalchemy import select

from spring_harness.cloud.db import SystemSettingRow, get_sessionmaker
from spring_harness.core.log import logger

# 缓存 key 与 TTL：TTL 是最终一致性兜底（网关写后延迟双删失败/遗漏时最多脏 60s）
CACHE_KEY = "sysconf:settings"
CACHE_TTL_SECONDS = 60


class DbSettings:
    """system_settings 的 DB 实现（满足 core.config.platform.PlatformSettings 协议），带 Redis 快照缓存。"""

    def __init__(self, cache: redis.Redis | None = None) -> None:
        # cache 为 None 时退化为纯直读（测试/Redis 未装配场景）
        self._cache = cache

    def get(self, key: str, default: str = "") -> str:
        return self.snapshot().get(key, default)

    def snapshot(self) -> dict[str, str]:
        if self._cache is not None:
            try:
                raw = self._cache.get(CACHE_KEY)
                if raw is not None:
                    return json.loads(raw)
            except redis.RedisError as e:
                logger.warning("设置快照缓存读取失败，回源 MySQL: {}", e)
        snapshot = self._load_from_db()
        if self._cache is not None:
            try:
                self._cache.set(CACHE_KEY, json.dumps(snapshot), ex=CACHE_TTL_SECONDS)
            except redis.RedisError as e:
                logger.warning("设置快照缓存回填失败（不影响本次读取）: {}", e)
        return snapshot

    def _load_from_db(self) -> dict[str, str]:
        with get_sessionmaker()() as s:
            return {
                row.setting_key: row.setting_value
                for row in s.scalars(select(SystemSettingRow)).all()
            }
