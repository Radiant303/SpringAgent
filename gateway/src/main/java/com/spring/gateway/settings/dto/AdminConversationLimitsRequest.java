package com.spring.gateway.settings.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 对话编辑与重试上限请求。
 *
 * @author hanbing
 * @since 2026-11-27
 * @param messageEditLimit 消息修改次数上限
 * @param answerRetryLimit 回答重试次数上限
 */
public record AdminConversationLimitsRequest(
        @NotNull @Min(0) @Max(6) Integer messageEditLimit,
        @NotNull @Min(0) @Max(6) Integer answerRetryLimit
) {
}
