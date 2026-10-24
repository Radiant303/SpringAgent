package com.spring.gateway.settings.dto;

import jakarta.validation.constraints.Size;

/**
 * 平台对外地址设置请求。
 *
 * @author hanbing
 * @since 2026-10-16
 * @param domain 平台对外域名（主机名，可带端口；空串 = 未配置）
 * @param https  对外访问是否使用 https
 */
public record AdminPlatformSettingsRequest(
        @Size(max = 128, message = "域名长度不能超过 128") String domain,
        boolean https
) {
}
