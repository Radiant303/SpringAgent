package com.spring.gateway.settings.dto;

import com.spring.gateway.common.ResourceLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 预扣预估档位请求；单位 tokens。
 *
 * @author hanbing
 * @since 2026-10-05
 * @param cacheReadTokens 预估缓存命中输入 tokens
 * @param inputTokens     预估无缓存输入 tokens
 * @param outputTokens    预估输出 tokens
 */
public record AdminBillingEstRequest(
        @NotNull @Min(value = 0, message = "档位不能为负") @Max(ResourceLimits.MAX_BILLING_TOKENS) Long cacheReadTokens,
        @NotNull @Min(value = 0, message = "档位不能为负") @Max(ResourceLimits.MAX_BILLING_TOKENS) Long inputTokens,
        @NotNull @Min(value = 0, message = "档位不能为负") @Max(ResourceLimits.MAX_BILLING_TOKENS) Long outputTokens
) {
}
