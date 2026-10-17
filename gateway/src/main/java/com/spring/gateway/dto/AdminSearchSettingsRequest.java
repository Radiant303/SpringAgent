package com.spring.gateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 搜索服务设置请求。
 *
 * @author hanbing
 * @since 2026-10-17
 */
public record AdminSearchSettingsRequest(
        @NotNull Boolean localEnabled,
        @NotBlank @Size(max = 32) String localProvider,
        @NotNull BigDecimal nativePoints,
        @NotNull Map<String, @NotNull @Valid ProviderSettings> providers) {

    /**
     * 搜索厂商设置。
     *
     * @author hanbing
     * @since 2026-10-17
     */
    public record ProviderSettings(
            @Size(max = 1024, message = "API key 最多 1024 个字符") String apiKey,
            Boolean clearApiKey,
            @NotNull BigDecimal points) {
    }
}
