package com.spring.gateway.settings.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.spring.gateway.common.CacheSecretCipher;
import com.spring.gateway.common.SearchProviders;
import com.spring.gateway.common.SystemSettingCachePolicy;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.settings.mapper.SystemSettingMapper;
import com.spring.gateway.settings.infrastructure.SystemSettingCache;
import com.spring.gateway.settings.service.SystemSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 系统设置加密缓存边界单元测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class SystemSettingServiceUnitTests {
    private final SystemSettingMapper mapper = mock(SystemSettingMapper.class);
    private final SystemSettingCache cache = mock(SystemSettingCache.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CacheSecretCipher cipher = CacheSecretCipher.load(Path.of("unused"),
            Base64.getEncoder().encodeToString(new byte[32]));
    private final SystemSettingServiceImpl service = new SystemSettingServiceImpl(mapper, cache, redis, json, cipher);

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void whitelistIsExactAndImmutable() {
        assertEquals(19, SystemSettingCachePolicy.PUBLIC_KEYS.size());
        assertEquals(Set.of("registration_open", "mail.register_enabled", "mail.register_domains", "work_max_bytes",
                "billing.est_cache_read_tokens", "billing.est_input_tokens", "billing.est_output_tokens",
                "mail.resend_interval_seconds", "mail.code_ttl_seconds", "community_link", "storage.type",
                "storage.reconcile_cron", "platform.domain", "platform.https", "search.local.enabled",
                "search.local.provider", "search.native.points", "search.providers.tavily.points",
                "search.providers.exa.points"), SystemSettingCachePolicy.PUBLIC_KEYS);
        assertEquals(Set.of("mail.username", "mail.auth_code", "storage.oss.endpoint", "storage.oss.bucket",
                "storage.oss.access_key_id", "storage.oss.access_key_secret", "search.providers.tavily.api_key",
                "search.providers.exa.api_key"), SystemSettingCachePolicy.SENSITIVE_KEYS);
        assertEquals(27, SystemSettingCachePolicy.CACHEABLE_KEYS.size());
        assertFalse(SystemSettingCachePolicy.CACHEABLE_KEYS.contains("default_model"));
        assertFalse(SystemSettingCachePolicy.CACHEABLE_KEYS.contains("subagents.searcher"));
        assertThrows(UnsupportedOperationException.class,
                () -> SystemSettingCachePolicy.CACHEABLE_KEYS.add("future.setting"));
    }

    @Test
    void registrationDomainsNormalizeDeduplicateAndUseRootLocale() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals("[\"qq.com\",\"edu.cn\",\"i.example\"]", service.serializeMailRegisterDomains(
                    List.of(" QQ.COM ", "edu.cn", "qq.com", "I.EXAMPLE")));
            assertEquals("[]", service.serializeMailRegisterDomains(List.of()));
        } finally {
            java.util.Locale.setDefault(original);
        }
        verifyNoInteractions(mapper, cache, redis);
    }

    @Test
    void registrationDomainsRejectMalformedValuesAndOverLimitLists() {
        for (String domain : List.of("", " ", "@qq.com", "https://qq.com", "qq.com:25", "*.qq.com",
                "qq..com", ".qq.com", "qq.com.", "-qq.com", "qq-.com", "qq.c-", "localhost",
                "qq_com.cn", "qq.com/path", "q q.com", "邮箱.cn", "K.com", "a".repeat(64) + ".com",
                "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62))) {
            var failure = assertThrows(com.spring.gateway.common.BizException.class,
                    () -> service.serializeMailRegisterDomains(List.of(domain)), domain);
            assertEquals(400, failure.getStatus());
        }
        assertThrows(com.spring.gateway.common.BizException.class,
                () -> service.serializeMailRegisterDomains(java.util.Arrays.asList("qq.com", null)));
        assertThrows(com.spring.gateway.common.BizException.class,
                () -> service.serializeMailRegisterDomains(java.util.Collections.nCopies(101, "qq.com")));
        verifyNoInteractions(mapper, cache, redis);
    }

    @Test
    void registrationDomainsAllowBoundaryLengthsAndOneHundredEntries() {
        String longest = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertEquals(253, longest.length());
        assertEquals(List.of(longest), json.readValue(service.serializeMailRegisterDomains(List.of(longest)), List.class));
        List<String> domains = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "d" + i + ".t").toList();
        assertEquals(domains, json.readValue(service.serializeMailRegisterDomains(domains), List.class));
        assertEquals("[\"a.b\",\"xn--fiqs8s.cn\"]", service.serializeMailRegisterDomains(List.of("a.b", "xn--fiqs8s.cn")));
    }

    @Test
    void registrationDomainJsonCapacityIsCheckedAfterNormalizationAndDeduplication() {
        String suffix = "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        List<String> atLimit = List.of("a".repeat(63) + suffix, "b".repeat(63) + suffix,
                "c".repeat(63) + suffix, "d".repeat(62) + suffix);
        assertEquals(1024, service.serializeMailRegisterDomains(atLimit).length());
        List<String> overLimit = List.of(atLimit.get(0), atLimit.get(1), atLimit.get(2), "d" + atLimit.get(3));
        assertEquals(1025, json.writeValueAsString(overLimit).length());
        var failure = assertThrows(com.spring.gateway.common.BizException.class,
                () -> service.serializeMailRegisterDomains(overLimit));
        assertEquals(400, failure.getStatus());
        assertTrue(failure.getMessage().contains("1024"));
        assertEquals(List.of(atLimit.getFirst()), json.readValue(service.serializeMailRegisterDomains(
                java.util.Collections.nCopies(100, " " + atLimit.getFirst().toUpperCase(java.util.Locale.ROOT) + " ")), List.class));
        verifyNoInteractions(mapper, cache, redis);
    }

    @Test
    void registrationDomainsReadSharedSnapshotAndDefaultToEmpty() {
        when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(snapshot(Map.of()));
        assertEquals(List.of(), service.getMailRegisterDomains());
        when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(snapshot(Map.of(
                SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true",
                SystemSetting.KEY_MAIL_REGISTER_DOMAINS, "[\" QQ.COM \",\"edu.cn\",\"qq.com\"]")));
        assertTrue(service.isMailRegisterEnabled());
        assertEquals(List.of("qq.com", "edu.cn"), service.getMailRegisterDomains());
        verifyNoInteractions(mapper);
    }

    @Test
    void malformedStoredRegistrationDomainsFailClosed() {
        for (String raw : List.of("null", "{}", "[null]", "[1]", "[\"*.qq.com\"]", "[] true", "broken")) {
            when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(snapshot(Map.of(
                    SystemSetting.KEY_MAIL_REGISTER_DOMAINS, raw)));
            var failure = assertThrows(com.spring.gateway.common.BizException.class, service::getMailRegisterDomains);
            assertEquals(500, failure.getStatus());
        }
    }

    @Test
    void registrationDomainsDatabaseFallbackAndWritesUseSharedCachePolicy() {
        when(mapper.selectList(any())).thenReturn(List.of(row(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, "[\"edu.cn\"]")));
        assertEquals(List.of("edu.cn"), service.getMailRegisterDomains());
        assertQueryKeys(SystemSettingCachePolicy.CACHEABLE_KEYS);
        assertEquals("[\"edu.cn\"]", writtenPayload().get(SystemSetting.KEY_MAIL_REGISTER_DOMAINS));
        service.set(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, service.serializeMailRegisterDomains(List.of("QQ.COM")));
        ArgumentCaptor<SystemSetting> written = ArgumentCaptor.forClass(SystemSetting.class);
        verify(mapper).upsert(written.capture());
        assertEquals(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, written.getValue().getSettingKey());
        assertEquals("[\"qq.com\"]", written.getValue().getSettingValue());
        verify(cache).invalidate();
    }

    @Test
    void databaseSnapshotEncryptsEverySensitiveFieldIncludingEmptyValues() {
        List<SystemSetting> rows = new java.util.ArrayList<>();
        Map<String, String> original = new HashMap<>();
        for (String key : SystemSettingCachePolicy.SENSITIVE_KEYS) {
            String value = key.equals("mail.auth_code") ? "" : "fixtureSecret-" + key;
            rows.add(row(key, value));
            original.put(key, value);
        }
        rows.add(row("community_link", "https://example.test"));
        rows.add(row("future.setting", "notCached"));
        rows.add(row("default_model", "notCached"));
        when(mapper.selectList(any())).thenReturn(rows);
        assertEquals("https://example.test", service.getCommunityLink());
        assertQueryKeys(SystemSettingCachePolicy.CACHEABLE_KEYS);
        Map<String, Object> payload = writtenPayload();
        assertEquals(9, payload.size());
        assertEquals("https://example.test", payload.get("community_link"));
        original.forEach((key, value) -> {
            assertNotEquals(value, payload.get(key));
            assertEquals(value, cipher.decrypt((String) payload.get(key), SystemSettingCache.CACHE_KEY, key));
        });
        assertFalse(payload.containsKey("future.setting"));
        assertFalse(payload.containsKey("default_model"));
        assertEquals("", rows.stream().filter(r -> r.getSettingKey().equals("mail.auth_code")).findFirst().orElseThrow().getSettingValue());
    }

    @Test
    void cachedCredentialsAreDecryptedAndMissingFieldsAreAllowed() {
        when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(snapshot(Map.of(
                "community_link", " cached ", "mail.username", " sender@example.test ",
                "mail.auth_code", " fixtureSecret ", "storage.oss.endpoint", " endpoint ",
                "storage.oss.bucket", " bucket ", "storage.oss.access_key_id", " id ",
                "storage.oss.access_key_secret", " ossSecret ")));
        assertEquals("cached", service.getCommunityLink());
        var mail = service.getMailCredentials();
        assertEquals("sender@example.test", mail.username());
        assertEquals("fixtureSecret", mail.authCode());
        assertEquals("MailCredentials[username=<redacted>, authCode=<redacted>]", mail.toString());
        var oss = service.getOssConfig();
        assertEquals(new SystemSettingService.OssConfig("endpoint", "bucket", "id", "ossSecret"), oss);
        assertTrue(oss.complete());
        assertEquals("OssConfig[endpoint=<redacted>, bucket=<redacted>, accessKeyId=<redacted>, accessKeySecret=<redacted>]", oss.toString());
        when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(snapshot(Map.of()));
        assertEquals(new SystemSettingService.MailCredentials("", ""), service.getMailCredentials());
        assertEquals(new SystemSettingService.OssConfig("", "", "", ""), service.getOssConfig());
        assertFalse(service.getOssConfig().complete());
        verifyNoInteractions(mapper);
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidSnapshotsAreCompareDeletedAndRebuiltWithSanitizedWarnings() {
        when(mapper.selectList(any())).thenReturn(List.of(row("community_link", "safe")));
        Logger logger = (Logger) LoggerFactory.getLogger(SystemSettingServiceImpl.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        String ciphertext = cipher.encrypt("fixtureSecret", SystemSettingCache.CACHE_KEY, "mail.auth_code");
        try {
            for (String raw : List.of(
                    "{}", "{\"community_link\":\"stale\"}", "[]", "null", "\"fixtureSecret\"",
                    "{\"version\":true,\"payload\":{}}", "{\"version\":1.0,\"payload\":{}}",
                    "{\"version\":2,\"payload\":{}}", "{\"version\":1,\"payload\":{},\"extra\":1}",
                    "{\"version\":1,\"payload\":null}", "{\"version\":1,\"payload\":[]}",
                    "{\"version\":1,\"payload\":{},\"version\":1}",
                    envelope(Map.of("future.setting", "fixtureSecret")),
                    envelope(Map.of("default_model", "fixtureSecret")),
                    envelope(Map.of("community_link", 42)), envelope(Map.of("community_link", true)),
                    "{\"version\":1,\"payload\":{\"community_link\":null}}",
                    envelope(Map.of("community_link", Map.of("value", "fixtureSecret"))),
                    envelope(Map.of("mail.auth_code", "fixtureSecret")),
                    envelope(Map.of("mail.username", ciphertext)),
                    envelope(Map.of("mail.auth_code", ciphertext.substring(0, ciphertext.length() - 4))),
                    snapshot(Map.of()) + " \"fixtureSecret\"", "{\"version\":1,\"payload\":")) {
                clearInvocations(redis, values, mapper);
                when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(raw);
                assertEquals("safe", service.getCommunityLink());
                ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass((Class) RedisScript.class);
                verify(redis).execute(script.capture(), eq(List.of(SystemSettingCache.CACHE_KEY)), eq(raw));
                assertTrue(script.getValue().getScriptAsString().contains("ARGV[1]"));
                assertTrue(script.getValue().getScriptAsString().contains("redis.call('DEL'"));
                verify(redis, never()).delete(anyString());
                assertEquals(Map.of("community_link", "safe"), writtenPayload());
                assertQueryKeys(SystemSettingCachePolicy.CACHEABLE_KEYS);
            }
            assertFalse(logs.list.isEmpty());
            for (ILoggingEvent event : logs.list) {
                assertFalse(event.getFormattedMessage().contains("fixtureSecret"));
                assertFalse(event.getFormattedMessage().contains(ciphertext));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void wrongMasterKeyRejectsWholeSnapshotRatherThanReturningPartialValues() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        CacheSecretCipher other = CacheSecretCipher.load(Path.of("unused"), Base64.getEncoder().encodeToString(otherKey));
        when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(envelope(Map.of("community_link", "stale",
                "mail.auth_code", other.encrypt("oldSecret", SystemSettingCache.CACHE_KEY, "mail.auth_code"))));
        when(mapper.selectList(any())).thenReturn(List.of(row("mail.auth_code", "newSecret"), row("community_link", "fresh")));
        assertEquals("fresh", service.getCommunityLink());
        assertQueryKeys(SystemSettingCachePolicy.CACHEABLE_KEYS);
    }

    @Test
    void registrationSwitchesUseSnapshot() {
        when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(snapshot(Map.of(
                "registration_open", "false", "mail.register_enabled", "true")));
        assertFalse(service.isRegistrationOpen());
        assertTrue(service.isMailRegisterEnabled());
        verifyNoInteractions(mapper);
    }

    @Test
    void transactionReadsAllWhitelistedRowsAndNeverTouchesRedis() {
        TransactionSynchronizationManager.initSynchronization();
        when(mapper.selectList(any())).thenReturn(List.of(row("mail.auth_code", "uncommitted")));
        assertEquals("uncommitted", service.getMailCredentials().authCode());
        assertQueryKeys(SystemSettingCachePolicy.CACHEABLE_KEYS);
        verifyNoInteractions(redis);
    }

    @Test
    void reconcileCronDirectReadIgnoresStaleSnapshotAndQueriesOnlyCron() {
        when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(snapshot(Map.of(
                SystemSetting.KEY_STORAGE_RECONCILE_CRON, "0 0 * * * *")));
        when(mapper.selectList(any())).thenReturn(List.of(
                row(SystemSetting.KEY_STORAGE_RECONCILE_CRON, " 0 0 */2 * * * ")));
        assertEquals("0 0 */2 * * *", service.loadStorageReconcileCron());
        assertQueryKeys(Set.of(SystemSetting.KEY_STORAGE_RECONCILE_CRON));
        verifyNoInteractions(redis, cache);
    }

    @Test
    void missingReconcileCronDirectReadUsesDefault() {
        when(mapper.selectList(any())).thenReturn(List.of());
        assertEquals(SystemSettingService.DEFAULT_RECONCILE_CRON, service.loadStorageReconcileCron());
        assertQueryKeys(Set.of(SystemSetting.KEY_STORAGE_RECONCILE_CRON));
        verifyNoInteractions(redis, cache);
    }

    @Test
    void unknownAndModelKeysRemainDatabaseOnly() {
        when(mapper.selectList(any())).thenReturn(List.of(row("default_model", "model"), row("future.setting", "future")));
        assertEquals("model", service.getDefaultModel());
        assertEquals("future", service.getString("future.setting", ""));
        verifyNoInteractions(redis);
    }

    @Test
    void allSensitiveWritesInvalidateAndKeepDatabaseValuesPlaintext() {
        for (String key : SystemSettingCachePolicy.SENSITIVE_KEYS) {
            service.set(key, "fixtureSecret");
        }
        service.set("registration_open", "false");
        service.set("mail.register_enabled", "true");
        verify(cache, times(10)).invalidate();
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(mapper, times(10)).upsert(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(row -> row.getSettingValue().startsWith("enc:v1:")));
        clearInvocations(cache);
        service.set("default_model", "model");
        service.set("subagents.searcher", "model");
        service.set("future.setting", "future");
        verifyNoInteractions(cache, redis);
    }

    @Test
    void settingWriteUsesSingleUpsertBeforeCacheInvalidation() {
        service.set(SystemSetting.KEY_STORAGE_TYPE, "local");
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        var order = inOrder(mapper, cache);
        order.verify(mapper).upsert(captor.capture());
        order.verify(cache).invalidate();
        order.verifyNoMoreInteractions();
        assertEquals(SystemSetting.KEY_STORAGE_TYPE, captor.getValue().getSettingKey());
        assertEquals("local", captor.getValue().getSettingValue());
        assertNotNull(captor.getValue().getUpdatedAt());
        verifyNoInteractions(redis);
    }

    @Test
    void failedUpsertDoesNotInvalidateCache() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(mapper.upsert(any(SystemSetting.class))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.set(SystemSetting.KEY_STORAGE_TYPE, "local")));
        verify(mapper).upsert(any(SystemSetting.class));
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(cache, redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchUsesOneDecryptedSnapshotForPresenceAndPoints() {
        when(values.get(SystemSettingCache.CACHE_KEY)).thenReturn(snapshot(Map.of(
                "search.native.points", "0.25", "search.providers.exa.points", "2",
                SearchProviders.apiKeySetting("tavily"), "fixtureSecret", SearchProviders.apiKeySetting("exa"), " ")));
        Map<String, Object> response = service.getSearchSettings();
        Map<String, Object> providers = (Map<String, Object>) response.get("providers");
        assertEquals(true, ((Map<String, Object>) providers.get("tavily")).get("apiKeyConfigured"));
        assertEquals(false, ((Map<String, Object>) providers.get("exa")).get("apiKeyConfigured"));
        assertEquals("0.25", response.get("nativePoints"));
        assertEquals("2", ((Map<String, Object>) providers.get("exa")).get("points"));
        assertFalse(response.toString().contains("fixtureSecret"));
        verify(values).get(SystemSettingCache.CACHE_KEY);
        verifyNoInteractions(mapper);
    }

    @Test
    void redisReadAndWriteFailuresStillReturnDatabaseValues() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("upstreamSecret"));
        doThrow(new IllegalStateException("upstreamSecret")).when(values)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(mapper.selectList(any())).thenReturn(List.of(row("mail.auth_code", "fixtureSecret")));
        assertEquals("fixtureSecret", service.getMailCredentials().authCode());
        assertQueryKeys(SystemSettingCachePolicy.CACHEABLE_KEYS);
    }

    @Test
    void databaseFailurePropagatesWithoutPublishingEmptySnapshot() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(mapper.selectList(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, service::getMailCredentials));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void encryptionFailureNeverPublishesPlaintext() {
        CacheSecretCipher broken = mock(CacheSecretCipher.class);
        when(broken.encrypt(anyString(), anyString(), any(String[].class))).thenThrow(new IllegalStateException("upstreamSecret"));
        SystemSettingServiceImpl guarded = new SystemSettingServiceImpl(mapper, cache, redis, json, broken);
        when(mapper.selectList(any())).thenReturn(List.of(row("mail.auth_code", "fixtureSecret")));
        assertEquals("fixtureSecret", guarded.getMailCredentials().authCode());
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void nullDatabaseValuesAreOmittedRatherThanCached() {
        when(mapper.selectList(any())).thenReturn(List.of(row("mail.auth_code", null)));
        assertEquals(new SystemSettingService.MailCredentials("", ""), service.getMailCredentials());
        assertTrue(writtenPayload().isEmpty());
    }

    private String snapshot(Map<String, String> plaintext) {
        Map<String, String> encoded = new HashMap<>();
        plaintext.forEach((key, value) -> encoded.put(key, SystemSettingCachePolicy.SENSITIVE_KEYS.contains(key)
                ? cipher.encrypt(value, SystemSettingCache.CACHE_KEY, key) : value));
        return envelope(encoded);
    }

    private String envelope(Map<String, ?> payload) {
        return json.writeValueAsString(Map.of("version", 1, "payload", payload));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> writtenPayload() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(SystemSettingCache.CACHE_KEY), captor.capture(), eq(60L), eq(TimeUnit.SECONDS));
        Map<String, Object> envelope = json.readValue(captor.getValue(), Map.class);
        assertEquals(Set.of("version", "payload"), envelope.keySet());
        assertEquals(1, envelope.get("version"));
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertQueryKeys(Set<String> expected) {
        ArgumentCaptor<QueryWrapper<SystemSetting>> captor = ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        QueryWrapper<SystemSetting> query = captor.getValue();
        assertEquals("setting_key,setting_value", query.getSqlSelect());
        assertTrue(query.getSqlSegment().contains("setting_key IN"));
        assertEquals(expected, Set.copyOf(query.getParamNameValuePairs().values()));
    }

    private SystemSetting row(String key, String value) {
        SystemSetting row = new SystemSetting();
        row.setSettingKey(key);
        row.setSettingValue(value);
        return row;
    }
}
