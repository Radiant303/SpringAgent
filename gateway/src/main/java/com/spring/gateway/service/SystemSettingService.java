package com.spring.gateway.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.ResourceLimits;
import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.mapper.SystemSettingMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 运行时系统设置。MySQL 为权威数据，Redis 全表快照缓存（读多写少的热点数据），
 * 写入后延迟双删保持最终一致；事务内读取直走 MySQL，保证读到本事务的写入。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingService.class);

    private final SystemSettingMapper systemSettingMapper;
    private final SystemSettingCache systemSettingCache;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 读布尔设置；键不存在时返回默认值
     *
     * @param key          设置键
     * @param defaultValue 键缺失时的默认值
     * @return 设置值
     */
    public boolean getBool(String key, boolean defaultValue) {
        String value = snapshot().get(key);
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
        String value = snapshot().get(key);
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
        String value = snapshot().get(key);
        return value == null ? defaultValue : value.trim();
    }

    /**
     * 是否开启 QQ 邮箱验证码注册；默认关闭
     *
     * @return true = 注册必须携带邮箱验证码
     */
    public boolean isMailRegisterEnabled() {
        return getBool(SystemSetting.KEY_MAIL_REGISTER_ENABLED, false);
    }

    /**
     * 发件 QQ 邮箱；默认空串（未配置）
     *
     * @return 发件邮箱
     */
    public String getMailUsername() {
        return getString(SystemSetting.KEY_MAIL_USERNAME, "");
    }

    /**
     * QQ 邮箱 SMTP 授权码；默认空串（未配置）
     *
     * @return 授权码
     */
    public String getMailAuthCode() {
        return getString(SystemSetting.KEY_MAIL_AUTH_CODE, "");
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
     * 阿里云 OSS endpoint；默认空串（未配置）
     *
     * @return endpoint
     */
    public String getOssEndpoint() {
        return getString(SystemSetting.KEY_OSS_ENDPOINT, "");
    }

    /**
     * 阿里云 OSS bucket；默认空串（未配置）
     *
     * @return bucket
     */
    public String getOssBucket() {
        return getString(SystemSetting.KEY_OSS_BUCKET, "");
    }

    /**
     * 阿里云 OSS AccessKey ID；默认空串（未配置）
     *
     * @return AccessKey ID
     */
    public String getOssAccessKeyId() {
        return getString(SystemSetting.KEY_OSS_ACCESS_KEY_ID, "");
    }

    /**
     * 阿里云 OSS AccessKey Secret；默认空串（未配置）
     *
     * @return AccessKey Secret
     */
    public String getOssAccessKeySecret() {
        return getString(SystemSetting.KEY_OSS_ACCESS_KEY_SECRET, "");
    }

    /**
     * 存储对账 cron（Spring 六段式）
     *
     * @return cron 表达式
     */
    public String getStorageReconcileCron() {
        return getString(SystemSetting.KEY_STORAGE_RECONCILE_CRON, DEFAULT_RECONCILE_CRON);
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
        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime previous = expression.next(ZonedDateTime.now());
        if (previous == null) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            ZonedDateTime next = expression.next(previous);
            if (next == null) {
                return false;
            }
            if (Duration.between(previous, next).getSeconds() < MIN_RECONCILE_INTERVAL_SECONDS) {
                return true;
            }
            previous = next;
        }
        return false;
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
        systemSettingMapper.insertOrUpdate(row);
        systemSettingCache.invalidate();
    }

    /** 设置全表快照：Redis 优先，未命中回源 MySQL 并回填；事务内直读 MySQL，保证读到本事务未提交的写入。 */
    private Map<String, String> snapshot() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            return loadFromDb();
        }
        try {
            String raw = redis.opsForValue().get(SystemSettingCache.CACHE_KEY);
            if (raw != null) {
                return objectMapper.readValue(raw, new TypeReference<>() {
                });
            }
        } catch (Exception e) {
            log.warn("设置快照缓存读取失败，回源 MySQL: {}", e.getMessage());
        }
        Map<String, String> snapshot = loadFromDb();
        try {
            redis.opsForValue().set(SystemSettingCache.CACHE_KEY, objectMapper.writeValueAsString(snapshot),
                    SystemSettingCache.CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("设置快照缓存回填失败（不影响本次读取）: {}", e.getMessage());
        }
        return snapshot;
    }

    private Map<String, String> loadFromDb() {
        Map<String, String> snapshot = new HashMap<>();
        for (SystemSetting row : systemSettingMapper.selectList(null)) {
            snapshot.put(row.getSettingKey(), row.getSettingValue());
        }
        return snapshot;
    }
}
