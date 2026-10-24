package com.spring.gateway.settings.dto;

import com.spring.gateway.common.ResourceLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 调整全局单工作区上限请求。
 *
 * @author hanbing
 * @since 2026-10-05
 * @param bytes 上限（字节，至少 1 MB）
 */
public record AdminWorkMaxBytesRequest(
        @NotNull @Min(value = ResourceLimits.MB, message = "上限至少 1 MB") @Max(ResourceLimits.MAX_QUOTA_BYTES)
        Long bytes
) {
}
