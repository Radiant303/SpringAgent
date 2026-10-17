package com.spring.gateway.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.dto.AdminMailSettingsRequest;
import com.spring.gateway.dto.AdminStorageSettingsRequest;
import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.mapper.SystemSettingMapper;
import com.spring.gateway.service.ReconcileScheduler;
import com.spring.gateway.service.SystemSettingCache;
import com.spring.gateway.service.SystemSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private final SystemSettingMapper mapper = mock(SystemSettingMapper.class);
    private final ReconcileScheduler scheduler = mock(ReconcileScheduler.class);
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
        ProxyFactory proxy = new ProxyFactory(new AdminSettingsController(service, scheduler));
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
    void mailCredentialsAndTimingCommitTogether() {
        controller.setMail(new AdminMailSettingsRequest(true, " SENDER@example.test ", " fakeSecret ", 60, 300));
        assertEquals("true", committed.get(SystemSetting.KEY_MAIL_REGISTER_ENABLED));
        assertEquals("sender@example.test", committed.get(SystemSetting.KEY_MAIL_USERNAME));
        assertEquals("fakeSecret", committed.get(SystemSetting.KEY_MAIL_AUTH_CODE));
        assertEquals("60", committed.get(SystemSetting.KEY_MAIL_RESEND_INTERVAL_SECONDS));
        assertEquals("300", committed.get(SystemSetting.KEY_MAIL_CODE_TTL_SECONDS));
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        assertEquals(1, reads.size());
        verifyNoInteractions(scheduler);
    }

    @Test
    void mailWriteFailureRollsBackAllPriorFields() {
        committed.put(SystemSetting.KEY_MAIL_USERNAME, "old@example.test");
        committed.put(SystemSetting.KEY_MAIL_AUTH_CODE, "oldSecret");
        Map<String, String> original = Map.copyOf(committed);
        failureKey = SystemSetting.KEY_MAIL_AUTH_CODE;
        assertThrows(IllegalStateException.class, () -> controller.setMail(
                new AdminMailSettingsRequest(true, "new@example.test", "newSecret", 60, 300)));
        assertEquals(original, committed);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verifyNoInteractions(scheduler);
    }

    @Test
    void enabledMailStillRequiresCompleteCredentialsAndBlankPreservesThem() {
        assertThrows(BizException.class, () -> controller.setMail(
                new AdminMailSettingsRequest(true, null, null, 60, 300)));
        assertTrue(committed.isEmpty());
        committed.put(SystemSetting.KEY_MAIL_USERNAME, "old@example.test");
        committed.put(SystemSetting.KEY_MAIL_AUTH_CODE, "oldSecret");
        controller.setMail(new AdminMailSettingsRequest(true, " ", " ", 60, 300));
        assertEquals("old@example.test", committed.get(SystemSetting.KEY_MAIL_USERNAME));
        assertEquals("oldSecret", committed.get(SystemSetting.KEY_MAIL_AUTH_CODE));
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
        AdminSettingsController direct = new AdminSettingsController(service, scheduler);
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
