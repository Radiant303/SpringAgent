package com.spring.gateway.identity.service.impl;

import com.spring.gateway.identity.service.AuthSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 登录会话单元测试。
 *
 * @author hanbing
 * @since 2026-11-12
 */
class AuthSessionServiceUnitTests {
    private final StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final AuthSessionServiceImpl service = new AuthSessionServiceImpl(redis, 60, 300);

    @Test
    void authenticationUsesOneRedisScript() {
        when(redis.execute(ArgumentMatchers.<DefaultRedisScript<String>>any(), anyList()))
                .thenReturn("ok:42", "expired", "invalid", null);
        String token = "00000000-0000-0000-0000-000000000000";

        AuthSessionService.Authentication valid = service.authenticate(token);
        assertTrue(valid.valid());
        assertEquals(42L, valid.userId());
        assertEquals(AuthSessionService.TokenFailure.EXPIRED, service.authenticate(token).failure());
        assertEquals(AuthSessionService.TokenFailure.INVALID, service.authenticate(token).failure());
        assertEquals(AuthSessionService.TokenFailure.INVALID, service.authenticate(token).failure());

        String hash = AuthSessionService.hashToken(token);
        verify(redis, org.mockito.Mockito.times(4)).execute(ArgumentMatchers.<DefaultRedisScript<String>>any(),
                org.mockito.ArgumentMatchers.eq(List.of("auth:access:" + hash, "auth:expired:" + hash)));
        verify(redis, never()).opsForValue();
        verify(redis, never()).hasKey(anyString());
    }
}
