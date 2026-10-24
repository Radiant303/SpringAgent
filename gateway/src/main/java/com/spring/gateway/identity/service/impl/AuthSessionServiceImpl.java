package com.spring.gateway.identity.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.identity.service.AuthSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Redis 登录会话。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Service
public class AuthSessionServiceImpl implements AuthSessionService {

    public static final String AUTH_ERROR_HEADER = "X-Auth-Error";
    public static final long TOMBSTONE_EXTRA_SECONDS = 60;

    private static final String ACCESS_PREFIX = "auth:access:";
    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String EXPIRED_PREFIX = "auth:expired:";
    private static final String SESSIONS_PREFIX = "auth:user:sessions:";
    private static final String VERSION_PREFIX = "auth:version:";
    private static final String LOGIN_LOCK_PREFIX = "auth:login:lock:";
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final DefaultRedisScript<String> CREATE_SCRIPT = script("""
            redis.call('setnx', KEYS[1], '1')
            local version = redis.call('get', KEYS[1])
            local access = cjson.encode({userId=ARGV[2], version=version})
            local refresh = cjson.encode({userId=ARGV[2], sessionId=ARGV[1], version=version, accessHash=ARGV[3]})
            redis.call('set', KEYS[2], access, 'PX', ARGV[5])
            redis.call('set', KEYS[3], refresh, 'PX', ARGV[6])
            redis.call('set', KEYS[5], '1', 'PX', ARGV[5] + 60000)
            redis.call('set', KEYS[6], '1', 'PX', ARGV[6] + 60000)
            redis.call('sadd', KEYS[4], ARGV[1] .. ':' .. ARGV[3] .. ':' .. ARGV[4])
            redis.call('pexpire', KEYS[4], ARGV[6] + 60000)
            return 'ok'
            """);

    private static final DefaultRedisScript<String> REFRESH_SCRIPT = script("""
            local raw = redis.call('get', KEYS[1])
            if not raw then
                if redis.call('exists', KEYS[2]) == 1 then return 'expired' else return 'invalid' end
            end
            local ok, session = pcall(cjson.decode, raw)
            if not ok or type(session) ~= 'table' or type(session.userId) ~= 'string'
                or type(session.sessionId) ~= 'string' or type(session.accessHash) ~= 'string' then
                return 'invalid'
            end
            local version = redis.call('get', 'auth:version:' .. session.userId)
            if not version or version ~= tostring(session.version) then return 'invalid' end
            local sessionsKey = 'auth:user:sessions:' .. session.userId
            redis.call('srem', sessionsKey, session.sessionId .. ':' .. session.accessHash .. ':' .. ARGV[1])
            redis.call('del', 'auth:access:' .. session.accessHash)
            redis.call('set', 'auth:expired:' .. session.accessHash, '1', 'PX', 60000)
            local access = cjson.encode({userId=session.userId, version=session.version})
            session.accessHash = ARGV[2]
            redis.call('set', KEYS[3], access, 'PX', ARGV[3])
            redis.call('set', KEYS[4], '1', 'PX', ARGV[3] + 60000)
            redis.call('set', KEYS[1], cjson.encode(session), 'PX', ARGV[4])
            redis.call('set', KEYS[2], '1', 'PX', ARGV[4] + 60000)
            redis.call('sadd', sessionsKey, session.sessionId .. ':' .. session.accessHash .. ':' .. ARGV[1])
            redis.call('pexpire', sessionsKey, ARGV[4] + 60000)
            return 'ok:' .. session.userId
            """);

    private static final DefaultRedisScript<String> LOGOUT_SCRIPT = script("""
            local raw = redis.call('get', KEYS[1])
            if not raw then return 'ok' end
            local ok, session = pcall(cjson.decode, raw)
            if not ok or type(session) ~= 'table' or type(session.userId) ~= 'string'
                or type(session.sessionId) ~= 'string' or type(session.accessHash) ~= 'string' then
                return 'ok'
            end
            redis.call('del', KEYS[1], KEYS[2], 'auth:access:' .. session.accessHash,
                'auth:expired:' .. session.accessHash)
            redis.call('srem', 'auth:user:sessions:' .. session.userId,
                session.sessionId .. ':' .. session.accessHash .. ':' .. ARGV[1])
            return 'ok'
            """);

    private static final DefaultRedisScript<Long> REVOKE_ALL_SCRIPT = new DefaultRedisScript<>("""
            local members = redis.call('smembers', KEYS[1])
            for _, member in ipairs(members) do
                local sessionId, accessHash, refreshHash = string.match(member, '^([^:]+):([^:]+):([^:]+)$')
                if sessionId then
                    redis.call('del', 'auth:access:' .. accessHash, 'auth:refresh:' .. refreshHash,
                        'auth:expired:' .. accessHash, 'auth:expired:' .. refreshHash)
                end
            end
            redis.call('del', KEYS[1])
            return redis.call('incr', KEYS[2])
            """, Long.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    @Autowired
    public AuthSessionServiceImpl(StringRedisTemplate redis, ObjectMapper objectMapper,
                                  @Value("${app.auth.access-ttl-seconds:3600}") long accessTtlSeconds,
                                  @Value("${app.auth.refresh-ttl-seconds:2592000}") long refreshTtlSeconds) {
        if (accessTtlSeconds <= 0 || refreshTtlSeconds <= 0) {
            throw new IllegalArgumentException("令牌有效期必须为正数");
        }
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /**
     * 创建独立登录会话。
     *
     * @param userId 用户 ID
     * @return 双令牌
     */
    public TokenBundle createSession(long userId) {
        String lockKey = LOGIN_LOCK_PREFIX + userId;
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(5));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BizException(429, "登录请求处理中，请稍后重试");
        }
        try {
            String accessToken = newToken();
            String refreshToken = newToken();
            String sessionId = newToken();
            String accessHash = hashToken(accessToken);
            String refreshHash = hashToken(refreshToken);
            redis.execute(CREATE_SCRIPT, List.of(
                            VERSION_PREFIX + userId, ACCESS_PREFIX + accessHash, REFRESH_PREFIX + refreshHash,
                            SESSIONS_PREFIX + userId, EXPIRED_PREFIX + accessHash, EXPIRED_PREFIX + refreshHash),
                    sessionId, String.valueOf(userId), accessHash, refreshHash,
                    String.valueOf(accessTtlSeconds * 1000), String.valueOf(refreshTtlSeconds * 1000));
            return new TokenBundle(accessToken, refreshToken, accessTtlSeconds, refreshTtlSeconds, userId);
        } finally {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), lockValue);
        }
    }

    /**
     * 校验访问令牌。
     *
     * @param token 访问令牌
     * @return 鉴权结果
     */
    public Authentication authenticate(String token) {
        if (!isToken(token)) {
            return Authentication.invalid();
        }
        String hash = hashToken(token);
        String raw = redis.opsForValue().get(ACCESS_PREFIX + hash);
        if (raw == null) {
            return Boolean.TRUE.equals(redis.hasKey(EXPIRED_PREFIX + hash))
                    ? Authentication.expired() : Authentication.invalid();
        }
        try {
            AccessValue value = objectMapper.readValue(raw, AccessValue.class);
            if (value == null) {
                return Authentication.invalid();
            }
            long userId = Long.parseLong(value.userId());
            String version = redis.opsForValue().get(VERSION_PREFIX + userId);
            if (version == null || !version.equals(value.version())) {
                return Authentication.invalid();
            }
            return Authentication.valid(userId);
        } catch (tools.jackson.core.JacksonException | NumberFormatException e) {
            return Authentication.invalid();
        }
    }

    /**
     * 更换访问令牌并延长刷新令牌有效期。
     *
     * @param refreshToken 刷新令牌
     * @return 新双令牌
     */
    public TokenBundle refresh(String refreshToken) {
        if (!isToken(refreshToken)) {
            throw new TokenException(TokenFailure.INVALID);
        }
        String accessToken = newToken();
        String refreshHash = hashToken(refreshToken);
        String accessHash = hashToken(accessToken);
        String result = redis.execute(REFRESH_SCRIPT, List.of(
                        REFRESH_PREFIX + refreshHash, EXPIRED_PREFIX + refreshHash,
                        ACCESS_PREFIX + accessHash, EXPIRED_PREFIX + accessHash),
                refreshHash, accessHash, String.valueOf(accessTtlSeconds * 1000),
                String.valueOf(refreshTtlSeconds * 1000));
        if (result != null && result.startsWith("ok:")) {
            long userId = Long.parseLong(result.substring(3));
            return new TokenBundle(accessToken, refreshToken, accessTtlSeconds, refreshTtlSeconds, userId);
        }
        throw new TokenException("expired".equals(result) ? TokenFailure.EXPIRED : TokenFailure.INVALID);
    }

    /**
     * 删除刷新令牌所属的单个会话。
     *
     * @param refreshToken 刷新令牌
     */
    public void logout(String refreshToken) {
        if (!isToken(refreshToken)) {
            return;
        }
        String refreshHash = hashToken(refreshToken);
        redis.execute(LOGOUT_SCRIPT, List.of(REFRESH_PREFIX + refreshHash, EXPIRED_PREFIX + refreshHash), refreshHash);
    }

    /**
     * 使用户全部会话失效并递增会话版本。
     *
     * @param userId 用户 ID
     */
    public void revokeAll(long userId) {
        redis.execute(REVOKE_ALL_SCRIPT, List.of(SESSIONS_PREFIX + userId, VERSION_PREFIX + userId));
    }

    /**
     * 计算令牌的 SHA-256 十六进制摘要。
     *
     * @param token 原始令牌
     * @return 哈希值
     */
    public static String hashToken(String token) {
        return AuthSessionService.hashToken(token);
    }

    private static boolean isToken(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    private static String newToken() {
        return UUID.randomUUID().toString();
    }

    private static DefaultRedisScript<String> script(String source) {
        return new DefaultRedisScript<>(source, String.class);
    }

    private record AccessValue(String userId, String version) {
    }
}
