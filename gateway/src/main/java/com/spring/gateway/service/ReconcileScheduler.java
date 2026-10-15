package com.spring.gateway.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledFuture;

/**
 * 存储对账任务的动态调度器。
 *
 * @author hanbing
 * @since 2026-10-15
 */
@Component
public class ReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);

    private final StorageReconcileTask task;
    private final SystemSettingService systemSettingService;
    private final ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> future;

    public ReconcileScheduler(StorageReconcileTask task, SystemSettingService systemSettingService) {
        this.task = task;
        this.systemSettingService = systemSettingService;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(1);
        this.scheduler.setThreadNamePrefix("storage-reconcile-");
        this.scheduler.initialize();
    }

    @PostConstruct
    void start() {
        apply(systemSettingService.getStorageReconcileCron());
    }

    /** 按新 cron 重排任务；表达式非法时回退默认值 */
    public synchronized void apply(String cron) {
        String effective = cron == null || cron.isBlank()
                ? SystemSettingService.DEFAULT_RECONCILE_CRON : cron.trim();
        if (!CronExpression.isValidExpression(effective)) {
            log.warn("对账 cron 非法（{}），回退默认值 {}", effective, SystemSettingService.DEFAULT_RECONCILE_CRON);
            effective = SystemSettingService.DEFAULT_RECONCILE_CRON;
        } else if (SystemSettingService.reconcileCronTooFrequent(effective)) {
            log.warn("对账 cron 相邻间隔低于下限（{}），回退默认值 {}",
                    effective, SystemSettingService.DEFAULT_RECONCILE_CRON);
            effective = SystemSettingService.DEFAULT_RECONCILE_CRON;
        }
        if (future != null) {
            future.cancel(false);
        }
        future = scheduler.schedule(task::reconcile, new CronTrigger(effective));
        log.info("存储对账已按 cron 调度: {}", effective);
    }

    @PreDestroy
    void stop() {
        if (future != null) {
            future.cancel(false);
        }
        scheduler.shutdown();
    }
}
