package com.spring.gateway.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * 系统设置缓存失效器单元测试。
 *
 * @author hanbing
 * @since 2026-10-16
 */
class SystemSettingCacheTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SystemSettingCache cache = new SystemSettingCache(redis);

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
        cache.shutdown();
    }

    @Test
    void invalidateDeletesImmediatelyAndAgainLater() {
        cache.invalidate();
        verify(redis).delete(SystemSettingCache.CACHE_KEY);
        verify(redis, timeout(2500).times(2)).delete(SystemSettingCache.CACHE_KEY);
    }

    @Test
    void transactionCommitDeletesOnlyAfterCommitAndAgainLater() {
        TransactionSynchronizationManager.initSynchronization();
        cache.invalidate();
        verifyNoInteractions(redis);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(s -> s.beforeCommit(false));
        verifyNoInteractions(redis);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(redis).delete(SystemSettingCache.CACHE_KEY);
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(redis, timeout(2500).times(2)).delete(SystemSettingCache.CACHE_KEY);
    }

    @Test
    void transactionRollbackNeverDeletesOrSchedulesAnEviction() {
        TransactionSynchronizationManager.initSynchronization();
        cache.invalidate();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(redis, after(1300).never()).delete(SystemSettingCache.CACHE_KEY);
    }
}
