package com.spring.gateway.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.billing.entity.UsageRecord;

import java.math.BigDecimal;

/**
 * 单次模型请求用量行。
 *
 * @author hanbing
 * @since 2026-10-18
 */
public record UsageRecordView(
        String requestId,
        String turnId,
        String sessionId,
        String userId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String modelId,
        String modelName,
        String agentName,
        boolean usageKnown,
        long inputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long outputTokens,
        BigDecimal costPoints,
        String status,
        String createdAt
) {

    /**
     * 从用量底账行转换
     *
     * @param row 用量底账行
     * @return 接口返回行
     */
    public static UsageRecordView of(UsageRecord row) {
        return of(row, true);
    }

    /**
     * 从用量底账行转换（不含模型 ID）
     *
     * @param row 用量底账行
     * @return 接口返回行
     */
    public static UsageRecordView forUser(UsageRecord row) {
        return of(row, false);
    }

    private static UsageRecordView of(UsageRecord row, boolean withModelId) {
        return new UsageRecordView(
                row.getRequestId(),
                row.getTurnId(),
                row.getSessionId(),
                String.valueOf(row.getUserId()),
                withModelId ? row.getModelId() : null,
                row.getModelName(),
                row.getAgentName(),
                Boolean.TRUE.equals(row.getUsageKnown()),
                row.getInputTokens(),
                row.getCacheReadTokens(),
                row.getCacheWriteTokens(),
                row.getOutputTokens(),
                row.getCostPoints(),
                row.getStatus(),
                TimeFormat.isoUtc(row.getCreatedAt()));
    }
}
