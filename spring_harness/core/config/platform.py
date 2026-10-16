from typing import Protocol

# 平台对外域名（主机名，可带端口，不含 scheme 与路径；空串 = 未配置）
KEY_PLATFORM_DOMAIN = "platform.domain"
# 平台对外访问是否使用 https（"true"/"false"）
KEY_PLATFORM_HTTPS = "platform.https"


class PlatformSettings(Protocol):
    def get(self, key: str, default: str = "") -> str:
        ...


_settings: PlatformSettings | None = None


def set_platform_settings(settings: PlatformSettings) -> None:
    global _settings
    _settings = settings


def platform_settings() -> PlatformSettings:
    if _settings is None:
        raise RuntimeError("平台设置未初始化，请先调用 set_platform_settings()")
    return _settings
