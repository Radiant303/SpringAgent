package com.spring.gateway.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求。
 *
 * @author hanbing
 * @since 2026-10-24
 * @param refreshToken 刷新令牌
 */
public record RefreshTokenRequest(
        @NotBlank(message = "刷新令牌不能为空")
        @JsonProperty("refresh_token") String refreshToken
) {
}
