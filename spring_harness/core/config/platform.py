"""平台设置读取口：system_settings 的键值访问。

本地入口没有 system_settings 可读，默认实现恒返回缺省值；
云端入口启动时换成 DbSettings（MySQL 权威 + Redis 快照缓存）。
"""

from __future__ import annotations

from typing import Protocol

# 平台对外域名（主机名，可带端口，不含 scheme 与路径；空串 = 未配置）
KEY_PLATFORM_DOMAIN = "platform.domain"
# 平台对外访问是否使用 https（"true"/"false"）
KEY_PLATFORM_HTTPS = "platform.https"


class PlatformSettings(Protocol):
    """system_settings 的读取口。"""

    def get(self, key: str, default: str = "") -> str:
        """按键取设置值；键缺失时返回 default。"""
        ...


class _EmptySettings:
    """本地入口默认：无平台设置可读。"""

    def get(self, key: str, default: str = "") -> str:
        return default


_settings: PlatformSettings = _EmptySettings()


def set_platform_settings(settings: PlatformSettings) -> None:
    """切换平台设置来源（云端入口启动时换成 DB 实现）。"""
    global _settings
    _settings = settings


def platform_settings() -> PlatformSettings:
    """当前平台设置来源。"""
    return _settings
