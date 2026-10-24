package com.spring.gateway.settings.infrastructure;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * system_settings 快照缓存的失效器。
 *
 * @author hanbing
 * @since 2026-10-16
 */
@Component
public class SystemSettingCache {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingCache.class);

    /** 白名单设置快照。 */
    public static final String CACHE_KEY = "sysconf:settings";

    /** 快照过期时间（秒）。 */
    public static final long CACHE_TTL_SECONDS = 60;

    /** 第二次删除的延迟：需大于"读方 cache miss 读库 + 回填缓存"的典型耗时。 */
    private static final long DELAYED_DELETE_MS = 1000;

    private final StringRedisTemplate redis;
    private final ScheduledExecutorService scheduler;

    /**
     * @param redis Redis 操作模板
     */
    public SystemSettingCache(StringRedisTemplate redis) {
        this.redis = redis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sys-settings-cache-evict");
                t.setDaemon(true);  // 守护线程：不阻塞 JVM 退出
                return t;
            }
        });
    }

    /**
     * 使设置快照缓存失效。事务内调用时登记到提交后执行；非事务环境立即执行。
     */
    public void invalidate() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictTwice();
                }
            });
        } else {
            evictTwice();
        }
    }

    /** 先删一次，再延迟删第二次。 */
    private void evictTwice() {
        deleteQuietly();
        scheduler.schedule(this::deleteQuietly, DELAYED_DELETE_MS, TimeUnit.MILLISECONDS);
    }

    /** 删除失败只告警：缓存有 TTL 兜底，删除失败不影响可用性。 */
    private void deleteQuietly() {
        try {
            redis.delete(CACHE_KEY);
        } catch (RuntimeException e) {
            log.warn("设置快照缓存删除失败: key={}", CACHE_KEY);
        }
    }

    /** 关闭调度器（应用停机时）。 */
    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
