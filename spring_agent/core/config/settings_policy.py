PUBLIC_KEYS: frozenset[str] = frozenset({
    "registration_open",
    "mail.register_enabled",
    "work_max_bytes",
    "billing.est_cache_read_tokens",
    "billing.est_input_tokens",
    "billing.est_output_tokens",
    "mail.resend_interval_seconds",
    "mail.code_ttl_seconds",
    "community_link",
    "storage.type",
    "storage.reconcile_cron",
    "platform.domain",
    "platform.https",
    "search.local.enabled",
    "search.local.provider",
    "search.native.points",
    "search.providers.tavily.points",
    "search.providers.exa.points",
})


SENSITIVE_KEYS: frozenset[str] = frozenset({
    "mail.username",
    "mail.auth_code",
    "storage.oss.endpoint",
    "storage.oss.bucket",
    "storage.oss.access_key_id",
    "storage.oss.access_key_secret",
    "search.providers.tavily.api_key",
    "search.providers.exa.api_key",
})

CACHEABLE_KEYS: frozenset[str] = PUBLIC_KEYS | SENSITIVE_KEYS


def require_cacheable_key(key: str) -> None:
    if key not in CACHEABLE_KEYS:
        raise ValueError("设置键不允许缓存")
