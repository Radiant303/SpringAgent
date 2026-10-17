package com.spring.gateway.dto;

/**
 * 搜索成功次数事件。
 *
 * @author hanbing
 * @since 2026-10-17
 */
public record SearchUsageEvent(
        String eventId,
        String source,
        String provider,
        Long searches,
        Long userId,
        String sessionId,
        String turnId,
        String requestId,
        String agentName
) {
}
