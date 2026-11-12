package com.spring.gateway.ws;

import org.springframework.web.socket.WebSocketHttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * WS 网关身份签名。
 *
 * @author hanbing
 * @since 2026-11-12
 */
final class WsIdentitySigner {
    static final String HEADER_USER_ID = "X-User-Id";
    static final String HEADER_CONNECTION_ID = "X-Connection-Id";
    static final String HEADER_EXPIRES = "X-Auth-Expires";
    static final String HEADER_SIGNATURE = "X-Internal-Signature";

    /** 握手身份签名有效期（秒）。 */
    private static final long SIGNATURE_TTL_SECONDS = 30;

    private final String secret;

    WsIdentitySigner(String secret) {
        this.secret = secret;
    }

    void sign(WebSocketHttpHeaders headers, long userId, String connectionId) {
        long expiresAt = Instant.now().plusSeconds(SIGNATURE_TTL_SECONDS).getEpochSecond();
        headers.set(HEADER_USER_ID, String.valueOf(userId));
        headers.set(HEADER_CONNECTION_ID, connectionId);
        headers.set(HEADER_EXPIRES, String.valueOf(expiresAt));
        headers.set(HEADER_SIGNATURE, signature(secret, userId, connectionId, expiresAt));
    }

    static String signature(String secret, long userId, String connectionId, long expiresAt) {
        String payload = "ws\n" + userId + "\n" + connectionId + "\n" + expiresAt;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
