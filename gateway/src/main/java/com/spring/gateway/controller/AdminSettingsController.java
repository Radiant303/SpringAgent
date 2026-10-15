package com.spring.gateway.controller;

import com.spring.gateway.common.RequiresOwner;
import com.spring.gateway.common.Result;
import com.spring.gateway.dto.AdminBillingEstRequest;
import com.spring.gateway.dto.AdminCommunityLinkRequest;
import com.spring.gateway.dto.AdminMailSettingsRequest;
import com.spring.gateway.dto.AdminRegistrationRequest;
import com.spring.gateway.dto.AdminStorageSettingsRequest;
import com.spring.gateway.dto.AdminWorkMaxBytesRequest;

import java.util.HashMap;
import com.spring.gateway.common.BizException;
import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.service.MailCodeService;
import com.spring.gateway.service.ReconcileScheduler;
import com.spring.gateway.service.SystemSettingService;
import com.spring.gateway.service.storage.StorageTypes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统设置接口：查看对 owner/admin 开放，修改仅站长。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final SystemSettingService systemSettingService;
    private final ReconcileScheduler reconcileScheduler;

    /**
     * 当前系统设置
     *
     * @return 设置快照
     */
    @GetMapping
    public Result<Map<String, Object>> get() {
        Map<String, Object> data = new HashMap<>(Map.of(
                "registrationOpen", systemSettingService.isRegistrationOpen(),
                "workMaxBytes", systemSettingService.getWorkMaxBytes(),
                "billingEstCacheReadTokens", systemSettingService.getLong(
                        SystemSetting.KEY_BILLING_EST_CACHE_READ_TOKENS, 90000L),
                "billingEstInputTokens", systemSettingService.getLong(
                        SystemSetting.KEY_BILLING_EST_INPUT_TOKENS, 10000L),
                "billingEstOutputTokens", systemSettingService.getLong(
                        SystemSetting.KEY_BILLING_EST_OUTPUT_TOKENS, 20000L),
                "mailRegisterEnabled", systemSettingService.isMailRegisterEnabled(),
                "mailUsername", systemSettingService.getMailUsername(),
                "mailAuthCodeConfigured", !systemSettingService.getMailAuthCode().isBlank(),
                "mailResendIntervalSeconds", systemSettingService.getMailResendIntervalSeconds(),
                "mailCodeTtlSeconds", systemSettingService.getMailCodeTtlSeconds()));
        data.put("communityLink", systemSettingService.getCommunityLink());
        data.put("storageType", systemSettingService.getStorageType());
        data.put("storageReconcileCron", systemSettingService.getStorageReconcileCron());
        data.put("ossEndpoint", systemSettingService.getOssEndpoint());
        data.put("ossBucket", systemSettingService.getOssBucket());
        data.put("ossAccessKeyId", systemSettingService.getOssAccessKeyId());
        data.put("ossAccessKeySecretConfigured",
                !systemSettingService.getOssAccessKeySecret().isBlank());
        return Result.ok(data);
    }

    /**
     * 设置注册开关
     *
     * @param req 开关
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/registration")
    public Result<Void> setRegistration(@Valid @RequestBody AdminRegistrationRequest req) {
        systemSettingService.set(SystemSetting.KEY_REGISTRATION_OPEN, String.valueOf(req.open()));
        return Result.ok(null);
    }

    /**
     * 设置全局单工作区上限
     *
     * @param req 上限字节数
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/work-max-bytes")
    public Result<Void> setWorkMaxBytes(@Valid @RequestBody AdminWorkMaxBytesRequest req) {
        systemSettingService.set(SystemSetting.KEY_WORK_MAX_BYTES, String.valueOf(req.bytes()));
        return Result.ok(null);
    }

    /**
     * 配置邮箱验证码注册。username/authCode 留空表示保持不变；
     * 开启时要求最终生效的发件邮箱与授权码都已就绪。
     *
     * @param req 邮箱设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/mail")
    public Result<Void> setMail(@Valid @RequestBody AdminMailSettingsRequest req) {
        String username = req.username() == null ? "" : req.username().trim();
        if (!username.isEmpty() && !MailCodeService.isValidEmail(username)) {
            throw new BizException(400, "发件邮箱格式不正确");
        }
        String authCode = req.authCode() == null ? "" : req.authCode().trim();
        // 生效值 = 本次传入优先，否则沿用已存储值
        String effectiveUsername = username.isEmpty() ? systemSettingService.getMailUsername() : username;
        boolean authReady = !authCode.isEmpty() || !systemSettingService.getMailAuthCode().isBlank();
        if (Boolean.TRUE.equals(req.enabled()) && (effectiveUsername.isBlank() || !authReady)) {
            throw new BizException(400, "开启前请先配置发件 QQ 邮箱与 SMTP 授权码");
        }
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, String.valueOf(req.enabled()));
        if (!username.isEmpty()) {
            systemSettingService.set(SystemSetting.KEY_MAIL_USERNAME, effectiveUsername.toLowerCase());
        }
        if (!authCode.isEmpty()) {
            systemSettingService.set(SystemSetting.KEY_MAIL_AUTH_CODE, authCode);
        }
        systemSettingService.set(SystemSetting.KEY_MAIL_RESEND_INTERVAL_SECONDS,
                String.valueOf(req.resendIntervalSeconds()));
        systemSettingService.set(SystemSetting.KEY_MAIL_CODE_TTL_SECONDS, String.valueOf(req.codeTtlSeconds()));
        return Result.ok(null);
    }

    /**
     * 设置官方社群链接。
     *
     * @param req 社群链接
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/community-link")
    public Result<Void> setCommunityLink(@Valid @RequestBody AdminCommunityLinkRequest req) {
        String link = req.link().trim();
        if (!link.isEmpty() && !link.startsWith("http://") && !link.startsWith("https://")) {
            throw new BizException(400, "链接必须以 http:// 或 https:// 开头");
        }
        systemSettingService.set(SystemSetting.KEY_COMMUNITY_LINK, link);
        return Result.ok(null);
    }

    /**
     * 配置文件存储。endpoint/bucket/accessKeyId/accessKeySecret 留空表示保持不变。
     *
     * @param req 存储设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/storage")
    public Result<Void> setStorage(@Valid @RequestBody AdminStorageSettingsRequest req) {
        String type = req.type().trim();
        if (!StorageTypes.ALL.contains(type)) {
            throw new BizException(400, "未知存储类型: " + type);
        }
        String endpoint = req.endpoint() == null ? "" : req.endpoint().trim();
        if (!endpoint.isEmpty() && !endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new BizException(400, "OSS endpoint 必须以 http:// 或 https:// 开头");
        }
        String bucket = req.bucket() == null ? "" : req.bucket().trim();
        String accessKeyId = req.accessKeyId() == null ? "" : req.accessKeyId().trim();
        String accessKeySecret = req.accessKeySecret() == null ? "" : req.accessKeySecret().trim();
        if (StorageTypes.ALIYUN_OSS.equals(type)) {
            // 生效值 = 本次传入优先，否则沿用已存储值
            String effectiveEndpoint = endpoint.isEmpty() ? systemSettingService.getOssEndpoint() : endpoint;
            String effectiveBucket = bucket.isEmpty() ? systemSettingService.getOssBucket() : bucket;
            String effectiveKeyId = accessKeyId.isEmpty() ? systemSettingService.getOssAccessKeyId() : accessKeyId;
            boolean secretReady = !accessKeySecret.isEmpty()
                    || !systemSettingService.getOssAccessKeySecret().isBlank();
            if (effectiveEndpoint.isBlank() || effectiveBucket.isBlank()
                    || effectiveKeyId.isBlank() || !secretReady) {
                throw new BizException(400, "切换到阿里云 OSS 前请补齐 endpoint、bucket 与 AccessKey");
            }
        }
        String cron = req.reconcileCron() == null ? "" : req.reconcileCron().trim();
        if (!cron.isEmpty() && !CronExpression.isValidExpression(cron)) {
            throw new BizException(400, "对账 cron 表达式非法（Spring 六段式：秒 分 时 日 月 周）");
        }
        systemSettingService.set(SystemSetting.KEY_STORAGE_TYPE, type);
        if (!endpoint.isEmpty()) {
            systemSettingService.set(SystemSetting.KEY_OSS_ENDPOINT, endpoint);
        }
        if (!bucket.isEmpty()) {
            systemSettingService.set(SystemSetting.KEY_OSS_BUCKET, bucket);
        }
        if (!accessKeyId.isEmpty()) {
            systemSettingService.set(SystemSetting.KEY_OSS_ACCESS_KEY_ID, accessKeyId);
        }
        if (!accessKeySecret.isEmpty()) {
            systemSettingService.set(SystemSetting.KEY_OSS_ACCESS_KEY_SECRET, accessKeySecret);
        }
        systemSettingService.set(SystemSetting.KEY_STORAGE_RECONCILE_CRON,
                cron.isEmpty() ? SystemSettingService.DEFAULT_RECONCILE_CRON : cron);
        reconcileScheduler.apply(cron);
        return Result.ok(null);
    }

    /**
     * 设置预扣预估档位
     *
     * @param req 三档 tokens 数
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/billing-est")
    public Result<Void> setBillingEst(@Valid @RequestBody AdminBillingEstRequest req) {
        systemSettingService.set(SystemSetting.KEY_BILLING_EST_CACHE_READ_TOKENS,
                String.valueOf(req.cacheReadTokens()));
        systemSettingService.set(SystemSetting.KEY_BILLING_EST_INPUT_TOKENS,
                String.valueOf(req.inputTokens()));
        systemSettingService.set(SystemSetting.KEY_BILLING_EST_OUTPUT_TOKENS,
                String.valueOf(req.outputTokens()));
        return Result.ok(null);
    }
}
