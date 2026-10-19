package com.spring.gateway.common;

import java.util.Set;

/**
 * 系统设置缓存白名单。
 *
 * @author hanbing
 * @since 2026-10-17
 */
public final class SystemSettingCachePolicy {

    public static final Set<String> SENSITIVE_KEYS = Set.of(
            "mail.username", "mail.auth_code",
            "storage.oss.endpoint", "storage.oss.bucket", "storage.oss.access_key_id",
            "storage.oss.access_key_secret", "search.providers.tavily.api_key", "search.providers.exa.api_key");

    public static final Set<String> PUBLIC_KEYS = Set.of(
            "registration_open",
            "mail.register_enabled",
            "mail.register_domains",
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
            "search.providers.exa.points");

    public static final Set<String> CACHEABLE_KEYS = java.util.stream.Stream.concat(
            PUBLIC_KEYS.stream(), SENSITIVE_KEYS.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private SystemSettingCachePolicy() {
    }
}
