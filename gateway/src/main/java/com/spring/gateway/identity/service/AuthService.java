package com.spring.gateway.identity.service;

import com.spring.gateway.identity.dto.ChangePasswordRequest;
import com.spring.gateway.identity.dto.LoginRequest;
import com.spring.gateway.identity.dto.RegisterRequest;
import com.spring.gateway.identity.dto.ResetPasswordRequest;
import com.spring.gateway.identity.dto.TokenResponse;
import com.spring.gateway.identity.dto.UserResponse;

/**
 * 认证与账户安全业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface AuthService {
    UserResponse register(RegisterRequest req);

    TokenResponse login(LoginRequest req);

    TokenResponse refresh(String refreshToken);

    void logout(String refreshToken);

    long sendSceneCode(String email, MailCodeService.Scene scene);

    void resetPassword(ResetPasswordRequest req);

    long sendBindCode(Long userId, String email);

    void bindEmail(Long userId, String email, String code);

    long sendMyCode(Long userId);

    void changePassword(Long userId, ChangePasswordRequest req);
}
