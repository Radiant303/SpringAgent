package com.spring.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 模型请求冻结参数。
 *
 * @author hanbing
 * @since 2026-10-13
 */
public record ReserveModelRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String requestId,
        @NotNull @Positive Long userId,
        @NotBlank @Size(max = 64) String turnId,
        @NotBlank @Size(max = 36) String sessionId,
        @NotBlank @Size(max = 256) String modelId,
        @NotBlank @Size(max = 32) String agentName
) {
}
