package com.spring.gateway.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JWT 工具类单元测试。不依赖 Spring 容器，直接实例化测试。
 *
 * @author hanbing
 * @since 2026-09-26
 */
class JwtUtilsTests {

    private static final String SECRET = "springagent-dev-secret-0123456789abcd";

    /**
     * 金标准 token（sub=123456789，username=golden，exp=9999999999），
     * 由 PyJWT 侧使用相同密钥与 claims 格式签发，用于验证跨语言 token 互认。
     */
    private static final String PYTHON_SIGNED_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJzdWIiOiIxMjM0NTY3ODkiLCJ1c2VybmFtZSI6ImdvbGRlbiIsImV4cCI6OTk5OTk5OTk5OX0."
            + "vv9iffQRG3MRJCY9-OYi5gg--B5Hl8amuJm8I5TTjN4";

    @Test
    @DisplayName("签发后能解析回 userId")
    void createAndParse() {
        JwtUtils jwt = new JwtUtils(SECRET, 10080);
        String token = jwt.createToken(123456789L, "demo");
        assertEquals(123456789L, jwt.parseUserId(token));
    }

    @Test
    @DisplayName("过期的 token 拒绝")
    void expiredReturnsNull() {
        // 有效期设为 -1 分钟，签发的 token 即刻过期
        JwtUtils jwt = new JwtUtils(SECRET, -1);
        String token = jwt.createToken(123456789L, "demo");
        assertNull(jwt.parseUserId(token));
    }

    @Test
    @DisplayName("篡改过的 token 拒绝")
    void tamperedReturnsNull() {
        JwtUtils jwt = new JwtUtils(SECRET, 10080);
        String token = jwt.createToken(123456789L, "demo");
        // 篡改签名段的第一个字符：签名是 HMAC 字节的 base64url，改动必导致验签失败
        int sigStart = token.lastIndexOf('.') + 1;
        char repl = token.charAt(sigStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, sigStart) + repl + token.substring(sigStart + 1);
        assertNull(jwt.parseUserId(tampered));
    }

    @Test
    @DisplayName("密钥不一致的 token 拒绝")
    void wrongSecretReturnsNull() {
        JwtUtils jwt = new JwtUtils(SECRET, 10080);
        String token = jwt.createToken(123456789L, "demo");
        JwtUtils other = new JwtUtils("another-secret-0123456789abcdefghij", 10080);
        assertNull(other.parseUserId(token));
    }

    @Test
    @DisplayName("互认：Python 签发的 token，Java 能验能解")
    void pythonSignedTokenParses() {
        JwtUtils jwt = new JwtUtils(SECRET, 10080);
        assertEquals(123456789L, jwt.parseUserId(PYTHON_SIGNED_TOKEN));
    }
}
