package com.spring.gateway.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * WS 网关身份签名测试。
 *
 * @author hanbing
 * @since 2026-11-12
 */
class WsIdentitySignerTests {
    @Test
    void signatureMatchesCrossLanguageVector() {
        assertEquals("25934bd95f948e75b908acabb9f5e2e9ee478fdaa49fe79225e2ca9cbde9af1e",
                WsIdentitySigner.signature("test-internal-token", 123L, "connection-1", 1893456000L));
    }

    @Test
    void signedHeadersCoverIdentityConnectionAndExpiry() {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        new WsIdentitySigner("test-internal-token").sign(headers, 123L, "connection-1");

        long expiresAt = Long.parseLong(headers.getFirst(WsIdentitySigner.HEADER_EXPIRES));
        assertEquals("123", headers.getFirst(WsIdentitySigner.HEADER_USER_ID));
        assertEquals("connection-1", headers.getFirst(WsIdentitySigner.HEADER_CONNECTION_ID));
        assertNotNull(headers.getFirst(WsIdentitySigner.HEADER_SIGNATURE));
        assertEquals(WsIdentitySigner.signature("test-internal-token", 123L, "connection-1", expiresAt),
                headers.getFirst(WsIdentitySigner.HEADER_SIGNATURE));
    }
}
