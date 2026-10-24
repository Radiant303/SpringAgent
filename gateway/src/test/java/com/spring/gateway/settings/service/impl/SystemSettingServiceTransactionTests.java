package com.spring.gateway.settings.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.spring.gateway.common.CacheSecretCipher;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.settings.mapper.SystemSettingMapper;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.settings.infrastructure.SystemSettingCache;
import com.spring.gateway.settings.service.SystemSettingWriter;
import com.spring.gateway.storage.infrastructure.ReconcileScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 系统设置事务与并发写入测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@SpringBootTest
class SystemSettingServiceTransactionTests {
    private static final String CRON_A = "0 0 * * * *";
    private static final String CRON_B = "0 0 */2 * * *";
    @Autowired private SystemSettingMapper database;
    @Autowired private SystemSettingService settings;
    @Autowired private SystemSettingWriter settingWriter;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;
    @MockitoBean private ReconcileScheduler scheduler;

    @Test
    void concurrentFirstWritesToSameKeyCommitWithTwoConnections() throws Exception {
        assertEquals(2, dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize());
        String key = "test.concurrent." + UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(12);
        List<Future<String>> writes = new ArrayList<>();
        try {
            assertNull(database.selectById(key));
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                settingWriter.set(key, "rolled-back-insert");
                throw new IllegalStateException("fixture rollback");
            }));
            assertNull(database.selectById(key));
            for (int i = 0; i < 12; i++) {
                String value = "fixture-" + i;
                writes.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return transaction.execute(status -> {
                        settingWriter.set(key, value);
                        assertEquals(value, settings.getString(key, ""));
                        return value;
                    });
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<String> committed = new ArrayList<>();
            for (Future<String> write : writes) {
                committed.add(write.get(20, TimeUnit.SECONDS));
            }
            assertEquals(12, committed.size());
            SystemSetting row = database.selectById(key);
            assertNotNull(row);
            assertTrue(committed.contains(row.getSettingValue()));
            assertNotNull(row.getUpdatedAt());
            String committedValue = row.getSettingValue();
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                settingWriter.set(key, "rolled-back-update");
                throw new IllegalStateException("fixture rollback");
            }));
            assertEquals(committedValue, settings.getString(key, ""));
            transaction.executeWithoutResult(status -> settingWriter.set(key, "final-update"));
            assertEquals("final-update", database.selectById(key).getSettingValue());
        } finally {
            start.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            } finally {
                database.deleteById(key);
            }
        }
    }

    @Test
    void newReadOnlyTransactionSeesLatestCommitInsideOldSnapshotAndAfterCommit() {
        TransactionTemplate independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        SystemSetting original = independent.execute(status -> database.selectById(SystemSetting.KEY_STORAGE_RECONCILE_CRON));
        SystemSettingMapper observed = mock(SystemSettingMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SystemSettingCache cache = mock(SystemSettingCache.class);
        AtomicReference<ConnectionHolder> outerConnection = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        when(observed.selectList(any())).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Object currentConnection = TransactionSynchronizationManager.getResource(dataSource);
            assertNotNull(currentConnection);
            assertNotSame(outerConnection.get(), currentConnection);
            QueryWrapper<SystemSetting> query = invocation.getArgument(0);
            assertEquals("setting_key,setting_value", query.getSqlSelect());
            query.getSqlSegment();
            assertEquals(Set.of(SystemSetting.KEY_STORAGE_RECONCILE_CRON),
                    Set.copyOf(query.getParamNameValuePairs().values()));
            reads.incrementAndGet();
            return database.selectList(query);
        });
        SystemSettingServiceImpl target = new SystemSettingServiceImpl(observed, cache, redis, new ObjectMapper(),
                CacheSecretCipher.load(Path.of("unused"), Base64.getEncoder().encodeToString(new byte[32])));
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        SystemSettingService service = (SystemSettingService) factory.getProxy();
        AtomicReference<String> afterCommitValue = new AtomicReference<>();
        try {
            independent.executeWithoutResult(status -> save(CRON_A));
            outer.executeWithoutResult(status -> {
                outerConnection.set((ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource));
                assertEquals(CRON_A, database.selectById(SystemSetting.KEY_STORAGE_RECONCILE_CRON).getSettingValue());
                independent.executeWithoutResult(inner -> save(CRON_B));
                assertEquals(CRON_A, database.selectById(SystemSetting.KEY_STORAGE_RECONCILE_CRON).getSettingValue());
                assertEquals(CRON_B, service.loadStorageReconcileCron());
                assertSame(outerConnection.get(), TransactionSynchronizationManager.getResource(dataSource));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        assertSame(outerConnection.get(), TransactionSynchronizationManager.getResource(dataSource));
                        afterCommitValue.set(service.loadStorageReconcileCron());
                    }
                });
            });
            assertEquals(CRON_B, afterCommitValue.get());
            assertEquals(2, reads.get());
            verifyNoInteractions(redis, cache);
        } finally {
            independent.executeWithoutResult(status -> {
                if (original == null) {
                    database.deleteById(SystemSetting.KEY_STORAGE_RECONCILE_CRON);
                } else {
                    database.upsert(original);
                }
            });
        }
    }

    private void save(String cron) {
        SystemSetting row = new SystemSetting();
        row.setSettingKey(SystemSetting.KEY_STORAGE_RECONCILE_CRON);
        row.setSettingValue(cron);
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        database.upsert(row);
    }
}
