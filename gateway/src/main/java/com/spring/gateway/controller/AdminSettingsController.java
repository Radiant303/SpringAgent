package com.spring.gateway.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.RequiresOwner;
import com.spring.gateway.common.ResourceLimits;
import com.spring.gateway.common.Result;
import com.spring.gateway.common.SearchProviders;
import com.spring.gateway.dto.AdminBillingEstRequest;
import com.spring.gateway.dto.AdminCommunityLinkRequest;
import com.spring.gateway.dto.AdminMailSettingsRequest;
import com.spring.gateway.dto.AdminPlatformSettingsRequest;
import com.spring.gateway.dto.AdminRegistrationRequest;
import com.spring.gateway.dto.AdminSearchSettingsRequest;
import com.spring.gateway.dto.AdminStorageSettingsRequest;
import com.spring.gateway.dto.AdminWorkMaxBytesRequest;
import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.service.MailCodeService;
import com.spring.gateway.service.ReconcileScheduler;
import com.spring.gateway.service.SystemSettingService;
import com.spring.gateway.service.storage.StorageTypes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final MailCodeService mailCodeService;
    private final UserMapper userMapper;

    /**
     * 当前系统设置
     *
     * @return 设置快照
     */
    @GetMapping
    public Result<Map<String, Object>> get() {
        SystemSettingService.MailCredentials mail = systemSettingService.getMailCredentials();
        SystemSettingService.OssConfig oss = systemSettingService.getOssConfig();
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
                "mailUsername", mail.username(),
                "mailAuthCodeConfigured", !mail.authCode().isBlank(),
                "mailResendIntervalSeconds", systemSettingService.getMailResendIntervalSeconds(),
                "mailCodeTtlSeconds", systemSettingService.getMailCodeTtlSeconds()));
        data.put("mailRegisterDomains", systemSettingService.getMailRegisterDomains());
        data.put("communityLink", systemSettingService.getCommunityLink());
        data.put("platformDomain", systemSettingService.getPlatformDomain());
        data.put("platformHttps", systemSettingService.isPlatformHttps());
        data.put("storageType", systemSettingService.getStorageType());
        data.put("storageReconcileCron", systemSettingService.getStorageReconcileCron());
        data.put("ossEndpoint", oss.endpoint());
        data.put("ossBucket", oss.bucket());
        data.put("ossAccessKeyId", oss.accessKeyId());
        data.put("ossAccessKeySecretConfigured", !oss.accessKeySecret().isBlank());
        data.put("search", systemSettingService.getSearchSettings());
        return Result.ok(data);
    }

    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/search")
    @Transactional
    public Result<Void> setSearch(@Valid @RequestBody AdminSearchSettingsRequest req) {
        String provider = req.localProvider().trim();
        if (!SearchProviders.isSupported(provider)) {
            throw new BizException(400, "未知搜索厂商: " + provider);
        }
        ResourceLimits.points(req.nativePoints(), false, "原生搜索积分");
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SearchProviders.LOCAL_ENABLED_KEY, String.valueOf(req.localEnabled()));
        values.put(SearchProviders.LOCAL_PROVIDER_KEY, provider);
        values.put(SearchProviders.NATIVE_POINTS_KEY, req.nativePoints().toPlainString());
        req.providers().forEach((id, settings) -> {
            if (!SearchProviders.isSupported(id)) {
                throw new BizException(400, "未知搜索厂商: " + id);
            }
            ResourceLimits.points(settings.points(), false, "搜索厂商积分");
            String apiKey = settings.apiKey() == null ? "" : settings.apiKey().trim();
            values.put(SearchProviders.pointsSetting(id), settings.points().toPlainString());
            if (!apiKey.isEmpty()) {
                values.put(SearchProviders.apiKeySetting(id), apiKey);
            }
        });
        values.forEach(systemSettingService::set);
        return Result.ok(null);
    }

    /**
     * 设置注册开关与邮箱域名白名单。
     *
     * @param req 注册设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/registration")
    @Transactional
    public Result<Void> setRegistration(@Valid @RequestBody AdminRegistrationRequest req) {
        String domains = req.emailDomains() == null ? null
                : systemSettingService.serializeMailRegisterDomains(req.emailDomains());
        systemSettingService.set(SystemSetting.KEY_REGISTRATION_OPEN, String.valueOf(req.open()));
        if (domains != null) {
            systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, domains);
        }
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
     * 给当前站长的绑定邮箱发送关闭功能验证码。
     *
     * @param userId 当前站长 ID
     * @return 重发间隔秒数
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/mail/send-disable-code")
    public Result<Map<String, Long>> sendMailDisableCode(
            @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        String email = boundEmail(userId);
        if (email.isBlank()) {
            throw new BizException(400, "当前账号未绑定邮箱");
        }
        return Result.ok(Map.of("resendAfterSeconds",
                mailCodeService.sendCode(email, MailCodeService.Scene.MAIL_DISABLE)));
    }

    /**
     * 配置邮箱功能。username/authCode 留空表示保持不变；
     * 开启时要求最终生效的发件邮箱与授权码都已就绪。
     *
     * @param userId 当前站长 ID
     * @param req    邮箱设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/mail")
    @Transactional
    public Result<Void> setMail(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                @Valid @RequestBody AdminMailSettingsRequest req) {
        String username = req.username() == null ? "" : req.username().trim();
        if (!username.isEmpty() && !MailCodeService.isValidEmail(username)) {
            throw new BizException(400, "发件邮箱格式不正确");
        }
        String authCode = req.authCode() == null ? "" : req.authCode().trim();
        SystemSettingService.MailCredentials current = systemSettingService.getMailCredentials();
        String effectiveUsername = username.isEmpty() ? current.username() : username;
        boolean authReady = !authCode.isEmpty() || !current.authCode().isBlank();
        if (Boolean.TRUE.equals(req.enabled()) && (effectiveUsername.isBlank() || !authReady)) {
            throw new BizException(400, "开启前请先配置发件 QQ 邮箱与 SMTP 授权码");
        }
        if (Boolean.FALSE.equals(req.enabled()) && systemSettingService.isMailRegisterEnabled()) {
            String email = boundEmail(userId);
            if (!email.isBlank()) {
                if (req.code() == null) {
                    throw new BizException(400, "关闭邮箱功能前请输入绑定邮箱收到的验证码");
                }
                mailCodeService.verifyCode(email, req.code(), MailCodeService.Scene.MAIL_DISABLE);
            }
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

    private String boundEmail(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return user.getEmail() == null ? "" : user.getEmail();
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

    /** 平台域名形态：主机名（可带端口），不含 scheme 与路径。 */
    private static final java.util.regex.Pattern PLATFORM_DOMAIN_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$");

    /**
     * 设置平台对外地址。domain 留空表示清除配置（本地图片读取工具将不可用）。
     *
     * @param req 平台设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/platform")
    public Result<Void> setPlatform(@Valid @RequestBody AdminPlatformSettingsRequest req) {
        String domain = req.domain() == null ? "" : req.domain().trim().toLowerCase();
        if (!domain.isEmpty() && !PLATFORM_DOMAIN_PATTERN.matcher(domain).matches()) {
            throw new BizException(400, "域名格式不正确：只填主机名（可带端口），不要带 http:// 或路径");
        }
        systemSettingService.set(SystemSetting.KEY_PLATFORM_DOMAIN, domain);
        systemSettingService.set(SystemSetting.KEY_PLATFORM_HTTPS, String.valueOf(req.https()));
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
    @Transactional
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
            SystemSettingService.OssConfig current = systemSettingService.getOssConfig();
            String effectiveEndpoint = endpoint.isEmpty() ? current.endpoint() : endpoint;
            String effectiveBucket = bucket.isEmpty() ? current.bucket() : bucket;
            String effectiveKeyId = accessKeyId.isEmpty() ? current.accessKeyId() : accessKeyId;
            boolean secretReady = !accessKeySecret.isEmpty() || !current.accessKeySecret().isBlank();
            if (effectiveEndpoint.isBlank() || effectiveBucket.isBlank()
                    || effectiveKeyId.isBlank() || !secretReady) {
                throw new BizException(400, "切换到阿里云 OSS 前请补齐 endpoint、bucket 与 AccessKey");
            }
        }
        String cron = req.reconcileCron() == null ? "" : req.reconcileCron().trim();
        if (!cron.isEmpty() && !CronExpression.isValidExpression(cron)) {
            throw new BizException(400, "对账 cron 表达式非法（Spring 六段式：秒 分 时 日 月 周）");
        }
        if (!cron.isEmpty() && SystemSettingService.reconcileCronTooFrequent(cron)) {
            throw new BizException(400, "对账频率过高：相邻触发间隔不得低于 "
                    + SystemSettingService.MIN_RECONCILE_INTERVAL_SECONDS / 60 + " 分钟");
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
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            reconcileScheduler.requestRefresh();
                        }
                    });
        } else {
            reconcileScheduler.requestRefresh();
        }
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
