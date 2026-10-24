package com.spring.gateway.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 登录会话业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface AuthSessionService {
    String AUTH_ERROR_HEADER = "X-Auth-Error";
    long TOMBSTONE_EXTRA_SECONDS = 60;

    TokenBundle createSession(long userId);

    Authentication authenticate(String token);

    TokenBundle refresh(String refreshToken);

    void logout(String refreshToken);

    void revokeAll(long userId);

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    enum TokenFailure {
        EXPIRED,
        INVALID
    }

    class TokenException extends RuntimeException {
        private final TokenFailure failure;

        public TokenException(TokenFailure failure) {
            super(failure == TokenFailure.EXPIRED ? "登录已过期" : "登录状态无效");
            this.failure = failure;
        }

        public TokenFailure getFailure() {
            return failure;
        }

        public String getErrorCode() {
            return failure == TokenFailure.EXPIRED ? "token_expired" : "token_invalid";
        }
    }

    record TokenBundle(String token, String refreshToken, long expiresIn, long refreshExpiresIn, long userId) {
    }

    record Authentication(TokenFailure failure, Long userId) {
        public static Authentication valid(long userId) {
            return new Authentication(null, userId);
        }

        public static Authentication expired() {
            return new Authentication(TokenFailure.EXPIRED, null);
        }

        public static Authentication invalid() {
            return new Authentication(TokenFailure.INVALID, null);
        }

        public boolean valid() {
            return userId != null;
        }
    }
}
