package com.spring.gateway.billing.dto;

import com.spring.gateway.common.ResourceLimits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 站长调账请求（充值/扣减积分）。
 *
 * @author hanbing
 * @since 2026-10-05
 * @param delta  变动额（正=充值，负=扣减，不能为 0；余额不许调成负）
 * @param reason 事由（记账备查）
 */
public record AdminPointsAdjustRequest(
        @NotNull @DecimalMin(ResourceLimits.MIN_ADJUST_TEXT) @DecimalMax(ResourceLimits.MAX_POINTS_TEXT)
        @Digits(integer = 7, fraction = ResourceLimits.POINTS_SCALE) BigDecimal delta,
        @NotBlank @Size(max = 255) String reason
) {
}
