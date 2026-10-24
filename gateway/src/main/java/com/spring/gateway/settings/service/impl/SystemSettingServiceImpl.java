package com.spring.gateway.settings.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.CacheSecretCipher;
import com.spring.gateway.common.ResourceLimits;
import com.spring.gateway.common.SearchProviders;
import com.spring.gateway.common.SystemSettingCachePolicy;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.settings.mapper.SystemSettingMapper;
import com.spring.gateway.settings.infrastructure.SystemSettingCache;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.settings.service.SystemSettingWriter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 运行时系统设置。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Service
@RequiredArgsConstructor
public class SystemSettingServiceImpl implements SystemSettingService, SystemSettingWriter {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingServiceImpl.class);

    private final SystemSettingMapper systemSettingMapper;
    private final SystemSettingCache systemSettingCache;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CacheSecretCipher cacheSecretCipher;

    /**
     * 读布尔设置；键不存在时返回默认值
     *
     * @param key          设置键
     * @param defaultValue 键缺失时的默认值
     * @return 设置值
     */
    public boolean getBool(String key, boolean defaultValue) {
        String value = readValue(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    /**
     * 是否开放注册；默认开放
     *
     * @return true = 开放
     */
    public boolean isRegistrationOpen() {
        return getBool(SystemSetting.KEY_REGISTRATION_OPEN, true);
    }

    /**
     * 读长整型设置；键不存在或值无法解析时返回默认值
     *
     * @param key          设置键
     * @param defaultValue 键缺失或值非法时的默认值
     * @return 设置值
     */
    public long getLong(String key, long defaultValue) {
        String value = readValue(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 全局单工作区上限（字节）；默认 20MB
     *
     * @return 上限字节数
     */
    public long getWorkMaxBytes() {
        return getLong(SystemSetting.KEY_WORK_MAX_BYTES, 20971520L);
    }

    /**
     * 读字符串设置；键不存在时返回默认值
     *
     * @param key          设置键
     * @param defaultValue 键缺失时的默认值
     * @return 设置值
     */
    public String getString(String key, String defaultValue) {
        String value = readValue(key);
        return value == null ? defaultValue : value.trim();
    }

    public BigDecimal getSearchPoints(String provider) {
        return searchPoints(snapshot(), provider);
    }

    public Map<String, Object> getSearchSettings() {
        Map<String, String> values = snapshot();
        Map<String, Object> providers = new LinkedHashMap<>();
        SearchProviders.ALL.forEach((id, label) -> providers.put(id, Map.of(
                "label", label,
                "apiKeyConfigured", !values.getOrDefault(SearchProviders.apiKeySetting(id), "").isBlank(),
                "points", searchPoints(values, id).toPlainString())));
        String provider = values.getOrDefault(SearchProviders.LOCAL_PROVIDER_KEY, SearchProviders.DEFAULT_PROVIDER);
        return Map.of(
                "localEnabled", Boolean.parseBoolean(values.getOrDefault(SearchProviders.LOCAL_ENABLED_KEY, "false")),
                "localProvider", SearchProviders.isSupported(provider) ? provider : SearchProviders.DEFAULT_PROVIDER,
                "nativePoints", searchPoints(values, "native").toPlainString(),
                "providers", providers);
    }

    private BigDecimal searchPoints(Map<String, String> values, String provider) {
        String key = SearchProviders.pointsSetting(provider);
        String value = values.get(key);
        if (value == null) {
            return BigDecimal.ONE;
        }
        try {
            BigDecimal points = new BigDecimal(value.trim());
            ResourceLimits.points(points, false, "搜索积分");
            return points;
        } catch (NumberFormatException | BizException e) {
            return BigDecimal.ONE;
        }
    }

    /**
     * 是否开启 QQ 邮箱验证码注册；默认关闭
     *
     * @return true = 注册必须携带邮箱验证码
     */
    public boolean isMailRegisterEnabled() {
        return getBool(SystemSetting.KEY_MAIL_REGISTER_ENABLED, false);
    }

    public List<String> getMailRegisterDomains() {
        String raw = readValue(SystemSetting.KEY_MAIL_REGISTER_DOMAINS);
        if (raw == null) {
            return List.of();
        }
        try {
            Object decoded = objectMapper.readerFor(Object.class)
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(raw);
            if (!(decoded instanceof List<?> domains)
                    || domains.stream().anyMatch(domain -> !(domain instanceof String))) {
                throw new IllegalArgumentException();
            }
            return normalizeMailRegisterDomains(domains.stream().map(String.class::cast).toList());
        } catch (RuntimeException e) {
            throw new BizException(500, "注册邮箱域名配置无效，请联系站长");
        }
    }

    public String serializeMailRegisterDomains(List<String> domains) {
        String serialized = objectMapper.writeValueAsString(normalizeMailRegisterDomains(domains));
        if (serialized.length() > 1024) {
            throw new BizException(400, "注册邮箱域名列表序列化后不能超过 1024 个字符");
        }
        return serialized;
    }

    private List<String> normalizeMailRegisterDomains(List<String> domains) {
        if (domains == null || domains.size() > 100) {
            throw new BizException(400, "注册邮箱域名最多 100 个");
        }
        return domains.stream().map(domain -> {
            if (domain == null) {
                throw new BizException(400, "邮箱域名格式不正确");
            }
            String trimmed = domain.trim();
            if (trimmed.isEmpty() || trimmed.length() > 253
                    || trimmed.chars().anyMatch(character -> character > 127)) {
                throw new BizException(400, "邮箱域名格式不正确");
            }
            String normalized = trimmed.toLowerCase(java.util.Locale.ROOT);
            String[] labels = normalized.split("\\.", -1);
            if (labels.length < 2) {
                throw new BizException(400, "邮箱域名格式不正确");
            }
            for (String label : labels) {
                if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                    throw new BizException(400, "邮箱域名格式不正确");
                }
            }
            return normalized;
        }).distinct().toList();
    }

    /**
     * 获取发件邮箱凭证。
     *
     * @return 发件邮箱凭证
     */
    public MailCredentials getMailCredentials() {
        Map<String, String> values = snapshot();
        return new MailCredentials(values.getOrDefault(SystemSetting.KEY_MAIL_USERNAME, "").trim(),
                values.getOrDefault(SystemSetting.KEY_MAIL_AUTH_CODE, "").trim());
    }

    /**
     * 同一邮箱重发验证码的最小间隔（秒）；默认 60
     *
     * @return 间隔秒数
     */
    public long getMailResendIntervalSeconds() {
        return getLong(SystemSetting.KEY_MAIL_RESEND_INTERVAL_SECONDS, 60L);
    }

    /**
     * 验证码有效期（秒）；默认 300
     *
     * @return 有效期秒数
     */
    public long getMailCodeTtlSeconds() {
        return getLong(SystemSetting.KEY_MAIL_CODE_TTL_SECONDS, 300L);
    }

    /**
     * 默认模型 ID；默认空串（未配置）
     *
     * @return 模型 ID
     */
    public String getDefaultModel() {
        return getString(SystemSetting.KEY_DEFAULT_MODEL, "");
    }

    /**
     * 官方社群链接；默认空串（未配置）
     *
     * @return 链接
     */
    public String getCommunityLink() {
        return getString(SystemSetting.KEY_COMMUNITY_LINK, "");
    }

    /**
     * 文件存储类型；默认本地
     *
     * @return 存储类型（local / aliyun_oss）
     */
    public String getStorageType() {
        return getString(SystemSetting.KEY_STORAGE_TYPE, "local");
    }

    /**
     * 获取阿里云 OSS 配置。
     *
     * @return OSS 配置
     */
    public OssConfig getOssConfig() {
        Map<String, String> values = snapshot();
        return new OssConfig(values.getOrDefault(SystemSetting.KEY_OSS_ENDPOINT, "").trim(),
                values.getOrDefault(SystemSetting.KEY_OSS_BUCKET, "").trim(),
                values.getOrDefault(SystemSetting.KEY_OSS_ACCESS_KEY_ID, "").trim(),
                values.getOrDefault(SystemSetting.KEY_OSS_ACCESS_KEY_SECRET, "").trim());
    }

    /**
     * 存储对账 cron（Spring 六段式）
     *
     * @return cron 表达式
     */
    public String getStorageReconcileCron() {
        return getString(SystemSetting.KEY_STORAGE_RECONCILE_CRON, DEFAULT_RECONCILE_CRON);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String loadStorageReconcileCron() {
        String value = loadFromDb(Set.of(SystemSetting.KEY_STORAGE_RECONCILE_CRON))
                .get(SystemSetting.KEY_STORAGE_RECONCILE_CRON);
        return value == null ? DEFAULT_RECONCILE_CRON : value.trim();
    }

    /**
     * 平台对外域名（主机名，可带端口）；默认空串（未配置）
     *
     * @return 域名
     */
    public String getPlatformDomain() {
        return getString(SystemSetting.KEY_PLATFORM_DOMAIN, "");
    }

    /**
     * 平台对外访问是否使用 https；默认否
     *
     * @return true = https
     */
    public boolean isPlatformHttps() {
        return getBool(SystemSetting.KEY_PLATFORM_HTTPS, false);
    }

    /** 存储对账 cron 默认值。 */
    public static final String DEFAULT_RECONCILE_CRON = "0 */30 * * * *";

    /** 相邻两次对账触发的最小间隔（秒）。 */
    public static final long MIN_RECONCILE_INTERVAL_SECONDS = 1800;

    /**
     * 校验 cron 是否存在低于下限的相邻触发间隔
     *
     * @param cron Spring 六段式 cron 表达式
     * @return true = 存在低于下限的相邻间隔
     */
    public static boolean reconcileCronTooFrequent(String cron) {
        return SystemSettingService.reconcileCronTooFrequent(cron);
    }

    /**
     * 写设置（upsert：存在更新值与 updated_at，不存在插入）
     *
     * @param key   设置键
     * @param value 设置值
     */
    public void set(String key, String value) {
        if (SystemSetting.KEY_WORK_MAX_BYTES.equals(key)
                || SystemSetting.KEY_BILLING_EST_CACHE_READ_TOKENS.equals(key)
                || SystemSetting.KEY_BILLING_EST_INPUT_TOKENS.equals(key)
                || SystemSetting.KEY_BILLING_EST_OUTPUT_TOKENS.equals(key)) {
            long numeric;
            try {
                numeric = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new BizException(400, "额度设置必须是整数");
            }
            boolean quota = SystemSetting.KEY_WORK_MAX_BYTES.equals(key);
            ResourceLimits.integer(numeric, quota ? ResourceLimits.MB : 0,
                    quota ? ResourceLimits.MAX_QUOTA_BYTES : ResourceLimits.MAX_BILLING_TOKENS,
                    quota ? "全局工作区配额（字节）" : "冻结预估 Token");
        }
        SystemSetting row = new SystemSetting();
        row.setSettingKey(key);
        row.setSettingValue(value);
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        systemSettingMapper.upsert(row);
        if (SystemSettingCachePolicy.CACHEABLE_KEYS.contains(key)) {
            systemSettingCache.invalidate();
        }
    }

    private String readValue(String key) {
        if (!SystemSettingCachePolicy.CACHEABLE_KEYS.contains(key)) {
            return loadFromDb(Set.of(key)).get(key);
        }
        return snapshot().get(key);
    }

    private Map<String, String> snapshot() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            return loadFromDb(SystemSettingCachePolicy.CACHEABLE_KEYS);
        }
        try {
            String raw = redis.opsForValue().get(SystemSettingCache.CACHE_KEY);
            if (raw != null) {
                try {
                    return decodeSnapshot(raw);
                } catch (Exception e) {
                    log.warn("设置快照缓存无效，重新加载");
                }
                deleteInvalidSnapshot(raw);
            }
        } catch (Exception e) {
            log.warn("设置快照缓存读取失败，回源数据库");
        }
        Map<String, String> snapshot = loadFromDb(SystemSettingCachePolicy.CACHEABLE_KEYS);
        try {
            Map<String, String> encoded = new HashMap<>();
            snapshot.forEach((key, value) -> encoded.put(key, SystemSettingCachePolicy.SENSITIVE_KEYS.contains(key)
                    ? cacheSecretCipher.encrypt(value, SystemSettingCache.CACHE_KEY, key) : value));
            String raw = objectMapper.writeValueAsString(Map.of("version", 1, "payload", encoded));
            redis.opsForValue().set(SystemSettingCache.CACHE_KEY, raw,
                    SystemSettingCache.CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("设置快照缓存回填失败");
        }
        return snapshot;
    }

    private Map<String, String> decodeSnapshot(String raw) {
        Object decoded = objectMapper.readerFor(Object.class)
                .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .readValue(raw);
        if (!(decoded instanceof Map<?, ?> envelope)
                || !envelope.keySet().equals(Set.of("version", "payload"))
                || !(envelope.get("version") instanceof Integer version) || version != 1
                || !(envelope.get("payload") instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException();
        }
        Map<String, String> validated = new HashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || !SystemSettingCachePolicy.CACHEABLE_KEYS.contains(key)
                    || !(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException();
            }
            validated.put(key, SystemSettingCachePolicy.SENSITIVE_KEYS.contains(key)
                    ? cacheSecretCipher.decrypt(value, SystemSettingCache.CACHE_KEY, key) : value);
        }
        return validated;
    }

    private void deleteInvalidSnapshot(String raw) {
        try {
            redis.execute(new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
                    Long.class), List.of(SystemSettingCache.CACHE_KEY), raw);
        } catch (RuntimeException e) {
            log.warn("无效设置快照缓存删除失败");
        }
    }

    private Map<String, String> loadFromDb(java.util.Collection<String> keys) {
        Map<String, String> values = new HashMap<>();
        QueryWrapper<SystemSetting> query = new QueryWrapper<SystemSetting>()
                .select("setting_key", "setting_value")
                .in("setting_key", keys);
        for (SystemSetting row : systemSettingMapper.selectList(query)) {
            if (keys.contains(row.getSettingKey()) && row.getSettingValue() != null) {
                values.put(row.getSettingKey(), row.getSettingValue());
            }
        }
        return values;
    }
}
