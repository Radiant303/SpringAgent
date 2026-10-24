package com.spring.gateway.settings.service;

/**
 * 存储对账调度刷新端口。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface ReconcileScheduleRefresher {
    void requestRefresh();
}
