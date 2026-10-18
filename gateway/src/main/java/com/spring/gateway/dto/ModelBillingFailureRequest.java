package com.spring.gateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 模型结算投递审计参数。
 *
 * @author hanbing
 * @since 2026-10-19
 */
public record ModelBillingFailureRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String requestId,
        @NotNull @Positive Long userId,
        @NotNull @Valid SettleModelRequest settlement,
        @NotNull @Pattern(regexp = "RETRYING|DELIVERED|DISCARDED") String deliveryStatus,
        @NotNull @PositiveOrZero Integer failureCount,
        @NotBlank String firstFailedAt,
        @NotBlank String lastFailedAt,
        @NotNull @Size(max = 256) String errorType,
        @NotNull String errorMessage
) {
}
