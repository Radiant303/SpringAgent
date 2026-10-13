package com.spring.gateway.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 模型资费卡请求。
 *
 * @author hanbing
 * @since 2026-10-13
 * @param modelId 模型配置 ID
 * @param inputPoints 无缓存输入费率
 * @param cacheReadPoints 缓存命中输入费率
 * @param cacheWritePoints 缓存写入费率
 * @param outputPoints 输出费率
 * @param enabled 是否启用；null 视为启用
 */
public record AdminRateCardRequest(
        @NotBlank @Size(max = 256) String modelId,
        @NotNull @DecimalMin(value = "0", message = "费率不能为负") BigDecimal inputPoints,
        @NotNull @DecimalMin(value = "0", message = "费率不能为负") BigDecimal cacheReadPoints,
        @NotNull @DecimalMin(value = "0", message = "费率不能为负") BigDecimal cacheWritePoints,
        @NotNull @DecimalMin(value = "0", message = "费率不能为负") BigDecimal outputPoints,
        Boolean enabled
) {
}
