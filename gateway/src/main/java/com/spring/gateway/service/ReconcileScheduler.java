package com.spring.gateway.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private volatile boolean stopped;

    @Autowired
    public ReconcileScheduler(StorageReconcileTask task, SystemSettingService systemSettingService) {
        this(task, systemSettingService, createScheduler());
    }

    ReconcileScheduler(StorageReconcileTask task, SystemSettingService systemSettingService,
                       ThreadPoolTaskScheduler scheduler) {
        this.task = task;
        this.systemSettingService = systemSettingService;
        this.scheduler = scheduler;
    }

    private static ThreadPoolTaskScheduler createScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("storage-reconcile-");
        scheduler.initialize();
        return scheduler;
    }

    @PostConstruct
    void start() {
        refresh();
    }

    public void requestRefresh() {
        if (stopped) {
            return;
        }
        try {
            scheduler.execute(() -> {
                try {
                    refresh();
                } catch (RuntimeException e) {
                    log.warn("存储对账调度刷新失败");
                }
            });
        } catch (RuntimeException e) {
            log.warn("存储对账调度刷新请求失败");
        }
    }

    /** 根据当前已提交的配置重排任务。 */
    public synchronized void refresh() {
        if (stopped) {
            return;
        }
        String cron = systemSettingService.loadStorageReconcileCron();
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
    synchronized void stop() {
        stopped = true;
        if (future != null) {
            future.cancel(false);
        }
        scheduler.shutdown();
    }
}
