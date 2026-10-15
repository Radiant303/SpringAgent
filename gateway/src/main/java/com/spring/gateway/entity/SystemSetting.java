package com.spring.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * system_settings 表的实体。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Data
@TableName("system_settings")
public class SystemSetting {

    /** 设置键：是否开放注册（"true"/"false"）。 */
    public static final String KEY_REGISTRATION_OPEN = "registration_open";

    /** 设置键：全局默认单 work 容量上限（字节，十进制数字串）。 */
    public static final String KEY_WORK_MAX_BYTES = "work_max_bytes";

    /** 设置键：预扣预估的缓存命中输入 tokens。 */
    public static final String KEY_BILLING_EST_CACHE_READ_TOKENS = "billing.est_cache_read_tokens";

    /** 设置键：预扣预估的无缓存输入 tokens。 */
    public static final String KEY_BILLING_EST_INPUT_TOKENS = "billing.est_input_tokens";

    /** 设置键：预扣预估的输出 tokens。 */
    public static final String KEY_BILLING_EST_OUTPUT_TOKENS = "billing.est_output_tokens";

    /** 设置键：是否开启 QQ 邮箱验证码注册（"true"/"false"）。 */
    public static final String KEY_MAIL_REGISTER_ENABLED = "mail.register_enabled";

    /** 设置键：发件 QQ 邮箱（SMTP 登录账号，From 必须与之一致）。 */
    public static final String KEY_MAIL_USERNAME = "mail.username";

    /** 设置键：QQ 邮箱 SMTP 授权码（敏感：接口只写不读，GET 只回"是否已配置"）。 */
    public static final String KEY_MAIL_AUTH_CODE = "mail.auth_code";

    /** 设置键：同一邮箱重发验证码的最小间隔（秒，十进制数字串）。 */
    public static final String KEY_MAIL_RESEND_INTERVAL_SECONDS = "mail.resend_interval_seconds";

    /** 设置键：验证码有效期（秒，十进制数字串）。 */
    public static final String KEY_MAIL_CODE_TTL_SECONDS = "mail.code_ttl_seconds";

    /** 设置键：默认模型 ID（必须指向 model_definitions 中已启用的行）。 */
    public static final String KEY_DEFAULT_MODEL = "default_model";

    /** 设置键：官方社群链接（空串 = 未配置）。 */
    public static final String KEY_COMMUNITY_LINK = "community_link";

    /** 设置键：文件存储类型（{@code local}/{@code aliyun_oss}，对应存储实现的 Bean 名）。 */
    public static final String KEY_STORAGE_TYPE = "storage.type";

    /** 设置键：阿里云 OSS endpoint。 */
    public static final String KEY_OSS_ENDPOINT = "storage.oss.endpoint";

    /** 设置键：阿里云 OSS bucket。 */
    public static final String KEY_OSS_BUCKET = "storage.oss.bucket";

    /** 设置键：阿里云 OSS AccessKey ID。 */
    public static final String KEY_OSS_ACCESS_KEY_ID = "storage.oss.access_key_id";

    /** 设置键：阿里云 OSS AccessKey Secret（敏感：接口只写不读，GET 只回"是否已配置"）。 */
    public static final String KEY_OSS_ACCESS_KEY_SECRET = "storage.oss.access_key_secret";

    /** 设置键：存储对账任务的 Spring cron 表达式。 */
    public static final String KEY_STORAGE_RECONCILE_CRON = "storage.reconcile_cron";

    /** 设置键：平台对外域名（主机名，可带端口，不含 scheme 与路径；空串 = 未配置）。 */
    public static final String KEY_PLATFORM_DOMAIN = "platform.domain";

    /** 设置键：平台对外访问是否使用 https（"true"/"false"）。 */
    public static final String KEY_PLATFORM_HTTPS = "platform.https";

    /** 设置键，主键（值由代码固定，手动赋值）。 */
    @TableId(type = IdType.INPUT)
    private String settingKey;

    /** 设置值。 */
    private String settingValue;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
