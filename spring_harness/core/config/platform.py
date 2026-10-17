from typing import Protocol

from spring_harness.core.config.settings_policy import require_cacheable_key

# 平台对外域名（主机名，可带端口，不含 scheme 与路径；空串 = 未配置）
KEY_PLATFORM_DOMAIN = "platform.domain"
# 平台对外访问是否使用 https（"true"/"false"）
KEY_PLATFORM_HTTPS = "platform.https"


class PlatformSettings(Protocol):
    def get(self, key: str, default: str = "") -> str:
        ...

    def snapshot(self) -> dict[str, str]:
        ...


class _EmptySettings:
    def get(self, key: str, default: str = "") -> str:
        require_cacheable_key(key)
        return default

    def snapshot(self) -> dict[str, str]:
        return {}


_settings: PlatformSettings = _EmptySettings()


def set_platform_settings(settings: PlatformSettings) -> None:
    global _settings
    _settings = settings


def platform_settings() -> PlatformSettings:
    return _settings
