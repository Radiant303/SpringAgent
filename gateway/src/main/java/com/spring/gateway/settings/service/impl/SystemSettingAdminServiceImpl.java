package com.spring.gateway.settings.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.ResourceLimits;
import com.spring.gateway.common.SearchProviders;
import com.spring.gateway.identity.service.MailCodeService;
import com.spring.gateway.identity.service.UserProfileService;
import com.spring.gateway.settings.dto.AdminBillingEstRequest;
import com.spring.gateway.settings.dto.AdminCommunityLinkRequest;
import com.spring.gateway.settings.dto.AdminMailSettingsRequest;
import com.spring.gateway.settings.dto.AdminPlatformSettingsRequest;
import com.spring.gateway.settings.dto.AdminRegistrationRequest;
import com.spring.gateway.settings.dto.AdminSearchSettingsRequest;
import com.spring.gateway.settings.dto.AdminStorageSettingsRequest;
import com.spring.gateway.settings.dto.AdminWorkMaxBytesRequest;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.settings.service.ReconcileScheduleRefresher;
import com.spring.gateway.settings.service.SystemSettingAdminService;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.settings.service.SystemSettingWriter;
import com.spring.gateway.storage.infrastructure.StorageTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统设置管理实现。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Service
@RequiredArgsConstructor
public class SystemSettingAdminServiceImpl implements SystemSettingAdminService {
    private static final java.util.regex.Pattern PLATFORM_DOMAIN_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$");

    private final SystemSettingService systemSettingService;
    private final SystemSettingWriter systemSettingWriter;
    private final MailCodeService mailCodeService;
    private final UserProfileService userProfileService;
    private final ReconcileScheduleRefresher reconcileScheduleRefresher;

    @Override
    public Map<String, Object> snapshot() {
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
        return data;
    }

    @Override
    @Transactional
    public void setSearch(AdminSearchSettingsRequest req) {
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
        values.forEach(systemSettingWriter::set);
    }

    @Override
    @Transactional
    public void setRegistration(AdminRegistrationRequest req) {
        String domains = req.emailDomains() == null ? null
                : systemSettingService.serializeMailRegisterDomains(req.emailDomains());
        systemSettingWriter.set(SystemSetting.KEY_REGISTRATION_OPEN, String.valueOf(req.open()));
        if (domains != null) {
            systemSettingWriter.set(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, domains);
        }
    }

    @Override
    public void setWorkMaxBytes(AdminWorkMaxBytesRequest req) {
        systemSettingWriter.set(SystemSetting.KEY_WORK_MAX_BYTES, String.valueOf(req.bytes()));
    }

    @Override
    public long sendMailDisableCode(long userId) {
        String email = userProfileService.boundEmail(userId);
        if (email.isBlank()) {
            throw new BizException(400, "当前账号未绑定邮箱");
        }
        return mailCodeService.sendCode(email, MailCodeService.Scene.MAIL_DISABLE);
    }

    @Override
    @Transactional
    public void setMail(long userId, AdminMailSettingsRequest req) {
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
            String email = userProfileService.boundEmail(userId);
            if (!email.isBlank()) {
                if (req.code() == null) {
                    throw new BizException(400, "关闭邮箱功能前请输入绑定邮箱收到的验证码");
                }
                mailCodeService.verifyCode(email, req.code(), MailCodeService.Scene.MAIL_DISABLE);
            }
        }
        systemSettingWriter.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, String.valueOf(req.enabled()));
        if (!username.isEmpty()) {
            systemSettingWriter.set(SystemSetting.KEY_MAIL_USERNAME, effectiveUsername.toLowerCase());
        }
        if (!authCode.isEmpty()) {
            systemSettingWriter.set(SystemSetting.KEY_MAIL_AUTH_CODE, authCode);
        }
        systemSettingWriter.set(SystemSetting.KEY_MAIL_RESEND_INTERVAL_SECONDS,
                String.valueOf(req.resendIntervalSeconds()));
        systemSettingWriter.set(SystemSetting.KEY_MAIL_CODE_TTL_SECONDS, String.valueOf(req.codeTtlSeconds()));
    }

    @Override
    public void setCommunityLink(AdminCommunityLinkRequest req) {
        String link = req.link().trim();
        if (!link.isEmpty() && !link.startsWith("http://") && !link.startsWith("https://")) {
            throw new BizException(400, "链接必须以 http:// 或 https:// 开头");
        }
        systemSettingWriter.set(SystemSetting.KEY_COMMUNITY_LINK, link);
    }

    @Override
    public void setPlatform(AdminPlatformSettingsRequest req) {
        String domain = req.domain() == null ? "" : req.domain().trim().toLowerCase();
        if (!domain.isEmpty() && !PLATFORM_DOMAIN_PATTERN.matcher(domain).matches()) {
            throw new BizException(400, "域名格式不正确：只填主机名（可带端口），不要带 http:// 或路径");
        }
        systemSettingWriter.set(SystemSetting.KEY_PLATFORM_DOMAIN, domain);
        systemSettingWriter.set(SystemSetting.KEY_PLATFORM_HTTPS, String.valueOf(req.https()));
    }

    @Override
    @Transactional
    public void setStorage(AdminStorageSettingsRequest req) {
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
        systemSettingWriter.set(SystemSetting.KEY_STORAGE_TYPE, type);
        if (!endpoint.isEmpty()) {
            systemSettingWriter.set(SystemSetting.KEY_OSS_ENDPOINT, endpoint);
        }
        if (!bucket.isEmpty()) {
            systemSettingWriter.set(SystemSetting.KEY_OSS_BUCKET, bucket);
        }
        if (!accessKeyId.isEmpty()) {
            systemSettingWriter.set(SystemSetting.KEY_OSS_ACCESS_KEY_ID, accessKeyId);
        }
        if (!accessKeySecret.isEmpty()) {
            systemSettingWriter.set(SystemSetting.KEY_OSS_ACCESS_KEY_SECRET, accessKeySecret);
        }
        systemSettingWriter.set(SystemSetting.KEY_STORAGE_RECONCILE_CRON,
                cron.isEmpty() ? SystemSettingService.DEFAULT_RECONCILE_CRON : cron);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            reconcileScheduleRefresher.requestRefresh();
                        }
                    });
        } else {
            reconcileScheduleRefresher.requestRefresh();
        }
    }

    @Override
    public void setBillingEst(AdminBillingEstRequest req) {
        systemSettingWriter.set(SystemSetting.KEY_BILLING_EST_CACHE_READ_TOKENS,
                String.valueOf(req.cacheReadTokens()));
        systemSettingWriter.set(SystemSetting.KEY_BILLING_EST_INPUT_TOKENS,
                String.valueOf(req.inputTokens()));
        systemSettingWriter.set(SystemSetting.KEY_BILLING_EST_OUTPUT_TOKENS,
                String.valueOf(req.outputTokens()));
    }
}
