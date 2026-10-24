package com.spring.gateway.identity.service.impl;

import com.spring.gateway.identity.service.AuthSessionService;

import com.spring.gateway.common.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 登录会话集成测试。
 *
 * @author hanbing
 * @since 2026-10-24
 */
class AuthSessionServiceTests {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private AuthSessionService sessions;
    private long userId;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory("127.0.0.1", 19900);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        sessions = service(60, 300);
        userId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }

    @AfterEach
    void tearDown() {
        if (sessions != null) {
            sessions.revokeAll(userId);
            redis.delete("auth:version:" + userId);
            redis.delete("auth:login:lock:" + userId);
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    private AuthSessionService service(long accessTtlSeconds, long refreshTtlSeconds) {
        return new AuthSessionServiceImpl(redis, JsonMapper.builder().build(), accessTtlSeconds, refreshTtlSeconds);
    }

    @Test
    @DisplayName("Redis 只存令牌哈希，同一用户的多个会话同时有效")
    void hashedTokensAndMultipleSessions() {
        AuthSessionService.TokenBundle first = sessions.createSession(userId);
        AuthSessionService.TokenBundle second = sessions.createSession(userId);

        assertNotEquals(first.token(), second.token());
        assertEquals(userId, sessions.authenticate(first.token()).userId());
        assertEquals(userId, sessions.authenticate(second.token()).userId());
        String accessKey = "auth:access:" + AuthSessionService.hashToken(first.token());
        String refreshKey = "auth:refresh:" + AuthSessionService.hashToken(first.refreshToken());
        String accessValue = redis.opsForValue().get(accessKey);
        String refreshValue = redis.opsForValue().get(refreshKey);
        assertNotNull(accessValue);
        assertNotNull(refreshValue);
        assertFalse(accessValue.contains(first.token()));
        assertFalse(refreshValue.contains(first.refreshToken()));
        assertTrue(redis.getExpire(accessKey, TimeUnit.SECONDS) <= 60);
        assertTrue(redis.getExpire(refreshKey, TimeUnit.SECONDS) > 290);
        assertNull(redis.opsForValue().get("auth:access:" + first.token()));
    }

    @Test
    @DisplayName("退出只删除当前会话，不影响同账号其他会话")
    void logoutOnlyRevokesCurrentSession() {
        AuthSessionService.TokenBundle first = sessions.createSession(userId);
        AuthSessionService.TokenBundle second = sessions.createSession(userId);

        sessions.logout(first.refreshToken());

        assertEquals(AuthSessionService.TokenFailure.INVALID, sessions.authenticate(first.token()).failure());
        assertEquals(AuthSessionService.TokenFailure.INVALID,
                assertThrows(AuthSessionService.TokenException.class,
                        () -> sessions.refresh(first.refreshToken())).getFailure());
        assertEquals(userId, sessions.authenticate(second.token()).userId());
        assertEquals(userId, sessions.authenticate(sessions.refresh(second.refreshToken()).token()).userId());
    }

    @Test
    @DisplayName("刷新更换访问令牌并延长刷新令牌有效期")
    void refreshRotatesAccessToken() {
        AuthSessionService.TokenBundle original = sessions.createSession(userId);

        AuthSessionService.TokenBundle renewed = sessions.refresh(original.refreshToken());

        assertNotEquals(original.token(), renewed.token());
        assertEquals(original.refreshToken(), renewed.refreshToken());
        assertEquals(AuthSessionService.TokenFailure.EXPIRED, sessions.authenticate(original.token()).failure());
        assertEquals(userId, sessions.authenticate(renewed.token()).userId());
        String refreshKey = "auth:refresh:" + AuthSessionService.hashToken(original.refreshToken());
        assertTrue(redis.getExpire(refreshKey, TimeUnit.SECONDS) > 290);
    }

    @Test
    @DisplayName("改密撤销使全部会话与刷新令牌失效")
    void revokeAllInvalidatesEverySession() {
        AuthSessionService.TokenBundle first = sessions.createSession(userId);
        AuthSessionService.TokenBundle second = sessions.createSession(userId);

        sessions.revokeAll(userId);

        assertEquals(AuthSessionService.TokenFailure.INVALID, sessions.authenticate(first.token()).failure());
        assertEquals(AuthSessionService.TokenFailure.INVALID, sessions.authenticate(second.token()).failure());
        for (AuthSessionService.TokenBundle bundle : new AuthSessionService.TokenBundle[]{first, second}) {
            assertEquals(AuthSessionService.TokenFailure.INVALID,
                    assertThrows(AuthSessionService.TokenException.class,
                            () -> sessions.refresh(bundle.refreshToken())).getFailure());
        }
        assertEquals(0L, redis.opsForSet().size("auth:user:sessions:" + userId));
    }

    @Test
    @DisplayName("自然过期返回 expired，伪造与撤销返回 invalid")
    void distinguishesExpiredAndInvalidTokens() throws InterruptedException {
        sessions = service(1, 2);
        AuthSessionService.TokenBundle bundle = sessions.createSession(userId);

        Thread.sleep(1100);

        assertEquals(AuthSessionService.TokenFailure.EXPIRED, sessions.authenticate(bundle.token()).failure());
        assertEquals(AuthSessionService.TokenFailure.INVALID, sessions.authenticate("forged").failure());
        Thread.sleep(1100);
        assertEquals(AuthSessionService.TokenFailure.EXPIRED,
                assertThrows(AuthSessionService.TokenException.class,
                        () -> sessions.refresh(bundle.refreshToken())).getFailure());
    }

    @Test
    @DisplayName("同一用户已有登录锁时拒绝并发创建会话")
    void concurrentLoginIsRejected() {
        String lockKey = "auth:login:lock:" + userId;
        redis.opsForValue().set(lockKey, "other-request", Duration.ofSeconds(5));

        BizException error = assertThrows(BizException.class, () -> sessions.createSession(userId));
        assertEquals(429, error.getStatus());
        assertEquals("other-request", redis.opsForValue().get(lockKey));

        redis.delete(lockKey);
        assertNotNull(sessions.createSession(userId));
    }

    @Test
    @DisplayName("刷新与退出并发执行后不会留下有效会话")
    void refreshAndLogoutAreAtomic() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                AuthSessionService.TokenBundle original = sessions.createSession(userId);
                CountDownLatch start = new CountDownLatch(1);
                Future<AuthSessionService.TokenBundle> refreshed = executor.submit(() -> {
                    start.await();
                    return sessions.refresh(original.refreshToken());
                });
                Future<?> loggedOut = executor.submit(() -> {
                    start.await();
                    sessions.logout(original.refreshToken());
                    return null;
                });
                start.countDown();
                AuthSessionService.TokenBundle renewed = null;
                try {
                    renewed = refreshed.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    assertInstanceOf(AuthSessionService.TokenException.class, e.getCause());
                }
                loggedOut.get(5, TimeUnit.SECONDS);
                if (renewed != null) {
                    assertFalse(sessions.authenticate(renewed.token()).valid());
                }
                assertNull(redis.opsForValue().get(
                        "auth:refresh:" + AuthSessionService.hashToken(original.refreshToken())));
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
