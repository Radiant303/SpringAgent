package com.spring.gateway.settings.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 注册设置请求。
 *
 * @author hanbing
 * @since 2026-10-05
 * @param open 是否开放注册
 * @param emailDomains 允许注册的邮箱域名；null = 保持不变
 * @param initialPoints 普通注册用户初始积分；null = 保持不变
 */
public record AdminRegistrationRequest(
        @NotNull Boolean open,
        List<String> emailDomains,
        BigDecimal initialPoints
) {
    public AdminRegistrationRequest(Boolean open) {
        this(open, null, null);
    }

    public AdminRegistrationRequest(Boolean open, List<String> emailDomains) {
        this(open, emailDomains, null);
    }
}
