package com.spring.gateway.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.dto.AdminMailSettingsRequest;
import com.spring.gateway.dto.AdminStorageSettingsRequest;
import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.SystemSettingMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.service.MailCodeService;
import com.spring.gateway.service.ReconcileScheduler;
import com.spring.gateway.service.SystemSettingCache;
import com.spring.gateway.service.SystemSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 系统设置接口事务与敏感字段单元测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class AdminSettingsControllerUnitTests {
    private static final Long USER_ID = 1L;
    private static final String BOUND_EMAIL = "owner@example.test";
    private final SystemSettingMapper mapper = mock(SystemSettingMapper.class);
    private final ReconcileScheduler scheduler = mock(ReconcileScheduler.class);
    private final MailCodeService mailCodes = mock(MailCodeService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SystemSettingCache cache = mock(SystemSettingCache.class);
    private final Map<String, String> committed = new HashMap<>();
    private final List<Set<String>> reads = new ArrayList<>();
    private final MemoryTransactionManager transactions = new MemoryTransactionManager();
    private Map<String, String> pending;
    private String failureKey;
    private AdminSettingsController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        java.util.concurrent.atomic.AtomicReference<String> cached = new java.util.concurrent.atomic.AtomicReference<>();
        when(values.get(SystemSettingCache.CACHE_KEY)).thenAnswer(invocation -> cached.get());
        doAnswer(invocation -> {
            cached.set(invocation.getArgument(1));
            return null;
        }).when(values).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class));
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            QueryWrapper<SystemSetting> query = invocation.getArgument(0);
            assertEquals("setting_key,setting_value", query.getSqlSelect());
            query.getSqlSegment();
            Set<String> keys = Set.copyOf(query.getParamNameValuePairs().values().stream()
                    .map(String.class::cast).toList());
            reads.add(keys);
            Map<String, String> data = pending == null ? committed : pending;
            return keys.stream().filter(data::containsKey).map(key -> {
                SystemSetting row = new SystemSetting();
                row.setSettingKey(key);
                row.setSettingValue(data.get(key));
                return row;
            }).toList();
        });
        when(mapper.upsert(any(SystemSetting.class))).thenAnswer(invocation -> {
            SystemSetting row = invocation.getArgument(0);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if (row.getSettingKey().equals(failureKey)) {
                throw new IllegalStateException("simulated write failure");
            }
            pending.put(row.getSettingKey(), row.getSettingValue());
            return 1;
        });
        SystemSettingService service = new SystemSettingService(mapper, cache, redis, new ObjectMapper(),
                com.spring.gateway.common.CacheSecretCipher.load(java.nio.file.Path.of("unused"),
                        java.util.Base64.getEncoder().encodeToString(new byte[32])));
        ProxyFactory proxy = new ProxyFactory(new AdminSettingsController(service, scheduler, mailCodes, users));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        controller = (AdminSettingsController) proxy.getProxy();
    }

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void getPreservesFieldsButNeverReturnsMailOrOssSecrets() {
        committed.put(SystemSetting.KEY_MAIL_USERNAME, "sender@example.test");
        committed.put(SystemSetting.KEY_MAIL_AUTH_CODE, "fakeMailSecret");
        seedOss();
        Map<String, Object> data = controller.get().getData();
        assertEquals("sender@example.test", data.get("mailUsername"));
        assertEquals(true, data.get("mailAuthCodeConfigured"));
        assertEquals("https://oss.example.test", data.get("ossEndpoint"));
        assertEquals("bucket", data.get("ossBucket"));
        assertEquals("fakeId", data.get("ossAccessKeyId"));
        assertEquals(true, data.get("ossAccessKeySecretConfigured"));
        assertTrue(data.keySet().containsAll(Set.of("registrationOpen", "workMaxBytes",
                "billingEstCacheReadTokens", "billingEstInputTokens", "billingEstOutputTokens",
                "mailRegisterEnabled", "mailResendIntervalSeconds", "mailCodeTtlSeconds", "communityLink",
                "platformDomain", "platformHttps", "storageType", "storageReconcileCron", "search")));
        assertFalse(data.containsKey("mailAuthCode"));
        assertFalse(data.containsKey("ossAccessKeySecret"));
        String json = new ObjectMapper().writeValueAsString(data);
        assertFalse(json.contains("fakeMailSecret"));
        assertFalse(json.contains("fakeOssSecret"));
        assertEquals(1, reads.stream().filter(keys -> keys.contains(SystemSetting.KEY_MAIL_AUTH_CODE)).count());
        assertEquals(1, reads.stream().filter(keys -> keys.contains(SystemSetting.KEY_OSS_ACCESS_KEY_SECRET)).count());
    }

    @Test
    void registrationSwitchAndNormalizedDomainsCommitTogether() {
        controller.setRegistration(new com.spring.gateway.dto.AdminRegistrationRequest(false,
                List.of(" QQ.COM ", "edu.cn", "qq.com")));
        assertEquals(Map.of(SystemSetting.KEY_REGISTRATION_OPEN, "false",
                SystemSetting.KEY_MAIL_REGISTER_DOMAINS, "[\"qq.com\",\"edu.cn\"]"), committed);
        assertEquals(List.of("qq.com", "edu.cn"), controller.get().getData().get("mailRegisterDomains"));
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        verify(cache, times(2)).invalidate();
        verifyNoInteractions(scheduler, mailCodes, users);
    }

    @Test
    void registrationRequestJsonPreservesOmittedAndNullDomainsAndClearsEmptyDomains() {
        ObjectMapper json = new ObjectMapper();
        committed.put(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, "[\"qq.com\"]");
        var omitted = json.readValue("{\"open\":true}", com.spring.gateway.dto.AdminRegistrationRequest.class);
        assertNull(omitted.emailDomains());
        assertEquals(new com.spring.gateway.dto.AdminRegistrationRequest(true), omitted);
        controller.setRegistration(omitted);
        controller.setRegistration(json.readValue("{\"open\":false,\"emailDomains\":null}",
                com.spring.gateway.dto.AdminRegistrationRequest.class));
        assertEquals("[\"qq.com\"]", committed.get(SystemSetting.KEY_MAIL_REGISTER_DOMAINS));
        verify(mapper, times(2)).upsert(any(SystemSetting.class));
        controller.setRegistration(json.readValue("{\"open\":true,\"emailDomains\":[]}",
                com.spring.gateway.dto.AdminRegistrationRequest.class));
        assertEquals("[]", committed.get(SystemSetting.KEY_MAIL_REGISTER_DOMAINS));
        assertEquals(List.of(), controller.get().getData().get("mailRegisterDomains"));
        assertEquals(3, transactions.commits);
    }

    @Test
    void invalidRegistrationDomainsRejectAllWritesBeforeSwitchChanges() {
        committed.put(SystemSetting.KEY_REGISTRATION_OPEN, "true");
        committed.put(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, "[\"qq.com\"]");
        Map<String, String> original = Map.copyOf(committed);
        var failure = assertThrows(BizException.class, () -> controller.setRegistration(
                new com.spring.gateway.dto.AdminRegistrationRequest(false, List.of("edu.cn", "*.qq.com"))));
        assertEquals(400, failure.getStatus());
        assertEquals(original, committed);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verify(mapper, never()).upsert(any(SystemSetting.class));
        verifyNoInteractions(cache);
    }

    @Test
    void oversizedRegistrationDomainsRejectBeforeAnySettingWrite() {
        committed.put(SystemSetting.KEY_REGISTRATION_OPEN, "true");
        committed.put(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, "[\"qq.com\"]");
        Map<String, String> original = Map.copyOf(committed);
        List<String> domains = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "a".repeat(60) + i + ".example").toList();
        var failure = assertThrows(BizException.class, () -> controller.setRegistration(
                new com.spring.gateway.dto.AdminRegistrationRequest(false, domains)));
        assertEquals(400, failure.getStatus());
        assertTrue(failure.getMessage().contains("1024"));
        assertEquals(original, committed);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verify(mapper, never()).upsert(any(SystemSetting.class));
        verifyNoInteractions(cache);
    }

    @Test
    void registrationDomainsWriteFailureRollsBackSwitchAndList() {
        committed.put(SystemSetting.KEY_REGISTRATION_OPEN, "true");
        committed.put(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, "[\"qq.com\"]");
        Map<String, String> original = Map.copyOf(committed);
        failureKey = SystemSetting.KEY_MAIL_REGISTER_DOMAINS;
        assertThrows(IllegalStateException.class, () -> controller.setRegistration(
                new com.spring.gateway.dto.AdminRegistrationRequest(false, List.of("edu.cn"))));
        assertEquals(original, committed);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verify(mapper, times(2)).upsert(any(SystemSetting.class));
    }

    @Test
    void settingsExposeEmptyRegistrationDomainsByDefault() {
        assertEquals(List.of(), controller.get().getData().get("mailRegisterDomains"));
        SystemSettingService service = mock(SystemSettingService.class);
        when(service.getMailCredentials()).thenReturn(new SystemSettingService.MailCredentials("", ""));
        when(service.getOssConfig()).thenReturn(new SystemSettingService.OssConfig("", "", "", ""));
        AdminSettingsController direct = new AdminSettingsController(service, scheduler, mailCodes, users);
        assertEquals(List.of(), direct.get().getData().get("mailRegisterDomains"));
    }

    @Test
    void mailCredentialsAndTimingCommitTogether() {
        controller.setMail(USER_ID,
                new AdminMailSettingsRequest(true, " SENDER@example.test ", " fakeSecret ", 60, 300, null));
        assertEquals("true", committed.get(SystemSetting.KEY_MAIL_REGISTER_ENABLED));
        assertEquals("sender@example.test", committed.get(SystemSetting.KEY_MAIL_USERNAME));
        assertEquals("fakeSecret", committed.get(SystemSetting.KEY_MAIL_AUTH_CODE));
        assertEquals("60", committed.get(SystemSetting.KEY_MAIL_RESEND_INTERVAL_SECONDS));
        assertEquals("300", committed.get(SystemSetting.KEY_MAIL_CODE_TTL_SECONDS));
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        assertEquals(1, reads.size());
        verifyNoInteractions(scheduler, mailCodes, users);
    }

    @Test
    void mailWriteFailureRollsBackAllPriorFields() {
        committed.put(SystemSetting.KEY_MAIL_USERNAME, "old@example.test");
        committed.put(SystemSetting.KEY_MAIL_AUTH_CODE, "oldSecret");
        Map<String, String> original = Map.copyOf(committed);
        failureKey = SystemSetting.KEY_MAIL_AUTH_CODE;
        assertThrows(IllegalStateException.class, () -> controller.setMail(USER_ID,
                new AdminMailSettingsRequest(true, "new@example.test", "newSecret", 60, 300, null)));
        assertEquals(original, committed);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verifyNoInteractions(scheduler);
    }

    @Test
    void enabledMailStillRequiresCompleteCredentialsAndBlankPreservesThem() {
        assertThrows(BizException.class, () -> controller.setMail(USER_ID,
                new AdminMailSettingsRequest(true, null, null, 60, 300, null)));
        assertTrue(committed.isEmpty());
        committed.put(SystemSetting.KEY_MAIL_USERNAME, "old@example.test");
        committed.put(SystemSetting.KEY_MAIL_AUTH_CODE, "oldSecret");
        controller.setMail(USER_ID, new AdminMailSettingsRequest(true, " ", " ", 60, 300, null));
        assertEquals("old@example.test", committed.get(SystemSetting.KEY_MAIL_USERNAME));
        assertEquals("oldSecret", committed.get(SystemSetting.KEY_MAIL_AUTH_CODE));
        verifyNoInteractions(mailCodes, users);
    }

    @Test
    void boundEnabledMailRejectsMissingCodeBeforeAnyWrite() {
        seedEnabledMail();
        bindEmail(BOUND_EMAIL);
        Map<String, String> original = Map.copyOf(committed);
        assertThrows(BizException.class, () -> controller.setMail(USER_ID,
                new AdminMailSettingsRequest(false, "new@example.test", "newSecret", 90, 600, null)));
        assertEquals(original, committed);
        assertNull(pending);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verify(mapper, never()).upsert(any(SystemSetting.class));
        verify(users).selectById(USER_ID);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(mailCodes, scheduler, cache);
    }

    @Test
    void disableCodeVerificationFailureRollsBackBeforeAnyWrite() {
        seedEnabledMail();
        bindEmail(BOUND_EMAIL);
        Map<String, String> original = Map.copyOf(committed);
        BizException failure = new BizException(400, "验证码错误或已过期");
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(original, pending);
            verify(mapper, never()).upsert(any(SystemSetting.class));
            throw failure;
        }).when(mailCodes).verifyCode(BOUND_EMAIL, "123456", MailCodeService.Scene.MAIL_DISABLE);
        assertSame(failure, assertThrows(BizException.class, () -> controller.setMail(USER_ID,
                new AdminMailSettingsRequest(false, "new@example.test", "newSecret", 90, 600, "123456"))));
        assertEquals(original, committed);
        assertNull(pending);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verify(mapper, never()).upsert(any(SystemSetting.class));
        verify(mailCodes).verifyCode(BOUND_EMAIL, "123456", MailCodeService.Scene.MAIL_DISABLE);
        verifyNoMoreInteractions(mailCodes);
        verifyNoInteractions(scheduler, cache);
    }

    @Test
    void dedicatedDisableCodeIsVerifiedBeforeAllSettingsCommit() {
        seedEnabledMail();
        bindEmail(BOUND_EMAIL);
        Map<String, String> original = Map.copyOf(committed);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(original, committed);
            assertEquals(original, pending);
            verify(mapper, never()).upsert(any(SystemSetting.class));
            return null;
        }).when(mailCodes).verifyCode(BOUND_EMAIL, "123456", MailCodeService.Scene.MAIL_DISABLE);
        controller.setMail(USER_ID,
                new AdminMailSettingsRequest(false, "new@example.test", "newSecret", 90, 600, "123456"));
        assertEquals(Map.of(
                SystemSetting.KEY_MAIL_REGISTER_ENABLED, "false",
                SystemSetting.KEY_MAIL_USERNAME, "new@example.test",
                SystemSetting.KEY_MAIL_AUTH_CODE, "newSecret",
                SystemSetting.KEY_MAIL_RESEND_INTERVAL_SECONDS, "90",
                SystemSetting.KEY_MAIL_CODE_TTL_SECONDS, "600"), committed);
        assertNull(pending);
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        var order = inOrder(users, mailCodes, mapper);
        order.verify(users).selectById(USER_ID);
        order.verify(mailCodes).verifyCode(BOUND_EMAIL, "123456", MailCodeService.Scene.MAIL_DISABLE);
        order.verify(mapper, times(5)).upsert(any(SystemSetting.class));
        verifyNoMoreInteractions(users, mailCodes);
        verifyNoInteractions(scheduler);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " \t \n"})
    void unboundEnabledMailCanBeDisabledWithoutCode(String email) {
        seedEnabledMail();
        bindEmail(email);
        Map<String, String> expected = new HashMap<>(committed);
        expected.put(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "false");
        controller.setMail(USER_ID, new AdminMailSettingsRequest(false, null, null, 60, 300, null));
        assertEquals(expected, committed);
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        verify(users).selectById(USER_ID);
        verifyNoInteractions(mailCodes, scheduler);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false"})
    void alreadyDisabledMailDoesNotNeedCodeOrBoundEmailLookup(String enabled) {
        seedEnabledMail();
        if (enabled == null) {
            committed.remove(SystemSetting.KEY_MAIL_REGISTER_ENABLED);
        } else {
            committed.put(SystemSetting.KEY_MAIL_REGISTER_ENABLED, enabled);
        }
        controller.setMail(USER_ID, new AdminMailSettingsRequest(false, null, null, 60, 300, null));
        assertEquals("false", committed.get(SystemSetting.KEY_MAIL_REGISTER_ENABLED));
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        verifyNoInteractions(mailCodes, users, scheduler);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enablingOrKeepingEnabledMailDoesNotNeedCode(boolean currentlyEnabled) {
        seedEnabledMail();
        committed.put(SystemSetting.KEY_MAIL_REGISTER_ENABLED, String.valueOf(currentlyEnabled));
        controller.setMail(USER_ID, new AdminMailSettingsRequest(true, null, null, 60, 300, null));
        assertEquals("true", committed.get(SystemSetting.KEY_MAIL_REGISTER_ENABLED));
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        verifyNoInteractions(mailCodes, users, scheduler);
    }

    @Test
    void sendDisableCodeUsesOnlyBoundEmailAndDedicatedScene() {
        bindEmail(BOUND_EMAIL);
        committed.put(SystemSetting.KEY_MAIL_USERNAME, "sender@example.test");
        Map<String, String> original = Map.copyOf(committed);
        when(mailCodes.sendCode(BOUND_EMAIL, MailCodeService.Scene.MAIL_DISABLE)).thenReturn(90L);
        assertEquals(Map.of("resendAfterSeconds", 90L), controller.sendMailDisableCode(USER_ID).getData());
        var order = inOrder(users, mailCodes);
        order.verify(users).selectById(USER_ID);
        order.verify(mailCodes).sendCode(BOUND_EMAIL, MailCodeService.Scene.MAIL_DISABLE);
        verifyNoMoreInteractions(users, mailCodes);
        verifyNoInteractions(mapper, cache, redis, scheduler);
        assertEquals(original, committed);
        assertEquals(0, transactions.commits);
        assertEquals(0, transactions.rollbacks);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " \t \n"})
    void sendDisableCodeRejectsUnboundEmail(String email) {
        bindEmail(email);
        committed.put(SystemSetting.KEY_MAIL_USERNAME, "sender@example.test");
        Map<String, String> original = Map.copyOf(committed);
        assertThrows(BizException.class, () -> controller.sendMailDisableCode(USER_ID));
        verify(users).selectById(USER_ID);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(mailCodes, mapper, cache, redis, scheduler);
        assertEquals(original, committed);
    }

    @Test
    void storageConfigurationCommitsBeforeRefreshIsQueued() {
        doAnswer(invocation -> {
            assertEquals(1, transactions.commits);
            assertNull(pending);
            assertEquals("0 0 * * * *", committed.get(SystemSetting.KEY_STORAGE_RECONCILE_CRON));
            return null;
        }).when(scheduler).requestRefresh();
        controller.setStorage(new AdminStorageSettingsRequest("aliyun_oss", "https://oss.example.test",
                "bucket", "fakeId", "fakeSecret", "0 0 * * * *"));
        assertEquals("aliyun_oss", committed.get(SystemSetting.KEY_STORAGE_TYPE));
        assertEquals("fakeSecret", committed.get(SystemSetting.KEY_OSS_ACCESS_KEY_SECRET));
        assertEquals("0 0 * * * *", committed.get(SystemSetting.KEY_STORAGE_RECONCILE_CRON));
        assertEquals(1, transactions.commits);
        verify(scheduler).requestRefresh();
        verifyNoMoreInteractions(scheduler);
        assertEquals(1, reads.size());
    }

    @Test
    void storageFailureRollsBackCredentialsAndDoesNotApplySchedule() {
        seedOss();
        Map<String, String> original = Map.copyOf(committed);
        failureKey = SystemSetting.KEY_STORAGE_RECONCILE_CRON;
        assertThrows(IllegalStateException.class, () -> controller.setStorage(new AdminStorageSettingsRequest(
                "aliyun_oss", "https://new.example.test", "new", "newId", "newSecret", "0 0 * * * *")));
        assertEquals(original, committed);
        assertEquals(1, transactions.rollbacks);
        verifyNoInteractions(scheduler);
    }

    @Test
    void ossSelectionStillRequiresCompleteCredentialsAndBlankPreservesThem() {
        assertThrows(BizException.class, () -> controller.setStorage(new AdminStorageSettingsRequest(
                "aliyun_oss", null, null, null, null, null)));
        seedOss();
        controller.setStorage(new AdminStorageSettingsRequest("aliyun_oss", " ", " ", " ", " ", ""));
        assertEquals("fakeOssSecret", committed.get(SystemSetting.KEY_OSS_ACCESS_KEY_SECRET));
        assertEquals(SystemSettingService.DEFAULT_RECONCILE_CRON,
                committed.get(SystemSetting.KEY_STORAGE_RECONCILE_CRON));
        verify(scheduler).requestRefresh();
    }

    @Test
    void directStorageCallWithoutTransactionQueuesRefreshAfterSaving() {
        SystemSettingService service = mock(SystemSettingService.class);
        AdminSettingsController direct = new AdminSettingsController(service, scheduler, mailCodes, users);
        direct.setStorage(new AdminStorageSettingsRequest("local", null, null, null, null, ""));
        var order = inOrder(service, scheduler);
        order.verify(service).set(SystemSetting.KEY_STORAGE_TYPE, "local");
        order.verify(service).set(SystemSetting.KEY_STORAGE_RECONCILE_CRON,
                SystemSettingService.DEFAULT_RECONCILE_CRON);
        order.verify(scheduler).requestRefresh();
    }

    private void seedOss() {
        committed.put(SystemSetting.KEY_OSS_ENDPOINT, "https://oss.example.test");
        committed.put(SystemSetting.KEY_OSS_BUCKET, "bucket");
        committed.put(SystemSetting.KEY_OSS_ACCESS_KEY_ID, "fakeId");
        committed.put(SystemSetting.KEY_OSS_ACCESS_KEY_SECRET, "fakeOssSecret");
    }

    private void seedEnabledMail() {
        committed.put(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true");
        committed.put(SystemSetting.KEY_MAIL_USERNAME, "sender@example.test");
        committed.put(SystemSetting.KEY_MAIL_AUTH_CODE, "oldSecret");
        committed.put(SystemSetting.KEY_MAIL_RESEND_INTERVAL_SECONDS, "60");
        committed.put(SystemSetting.KEY_MAIL_CODE_TTL_SECONDS, "300");
    }

    private void bindEmail(String email) {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail(email);
        when(users.selectById(USER_ID)).thenReturn(user);
    }

    private class MemoryTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            pending = new HashMap<>(committed);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            verifyNoInteractions(scheduler);
            committed.clear();
            committed.putAll(pending);
            pending = null;
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            pending = null;
            rollbacks++;
        }
    }
}
