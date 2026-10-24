package com.spring.gateway.catalog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 管理后台的模型资费卡行。
 *
 * @author hanbing
 * @since 2026-10-05
 * @param modelId 模型配置 ID
 * @param inputPoints 无缓存输入费率
 * @param cacheReadPoints 缓存命中输入费率
 * @param cacheWritePoints 缓存写入费率
 * @param outputPoints 输出费率
 * @param enabled 是否启用
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record AdminRateCardView(
        @JsonProperty("model_id") String modelId,
        @JsonProperty("input_points") BigDecimal inputPoints,
        @JsonProperty("cache_read_points") BigDecimal cacheReadPoints,
        @JsonProperty("cache_write_points") BigDecimal cacheWritePoints,
        @JsonProperty("output_points") BigDecimal outputPoints,
        Boolean enabled,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {
}
