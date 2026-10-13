package com.spring.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 单次模型请求用量。
 *
 * @author hanbing
 * @since 2026-10-13
 */
public record SettleModelRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String requestId,
        @NotNull @Positive Long userId,
        @NotNull @Pattern(regexp = "finished|cancelled|error") String status,
        @NotNull Boolean usageKnown,
        @NotNull @PositiveOrZero Long inputTokens,
        @NotNull @PositiveOrZero Long cacheReadTokens,
        @NotNull @PositiveOrZero Long cacheWriteTokens,
        @NotNull @PositiveOrZero Long outputTokens
) {
}
