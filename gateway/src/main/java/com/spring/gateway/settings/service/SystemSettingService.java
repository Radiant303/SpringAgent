package com.spring.gateway.settings.service;

import org.springframework.scheduling.support.CronExpression;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 运行时系统设置。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface SystemSettingService {
    String DEFAULT_RECONCILE_CRON = "0 */30 * * * *";
    long MIN_RECONCILE_INTERVAL_SECONDS = 1800;

    boolean getBool(String key, boolean defaultValue);

    boolean isRegistrationOpen();

    long getLong(String key, long defaultValue);

    long getWorkMaxBytes();

    String getString(String key, String defaultValue);

    BigDecimal getSearchPoints(String provider);

    Map<String, Object> getSearchSettings();

    boolean isMailRegisterEnabled();

    List<String> getMailRegisterDomains();

    String serializeMailRegisterDomains(List<String> domains);

    MailCredentials getMailCredentials();

    long getMailResendIntervalSeconds();

    long getMailCodeTtlSeconds();

    String getDefaultModel();

    String getCommunityLink();

    String getStorageType();

    OssConfig getOssConfig();

    String getStorageReconcileCron();

    String loadStorageReconcileCron();

    String getPlatformDomain();

    boolean isPlatformHttps();

    static boolean reconcileCronTooFrequent(String cron) {
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

    record MailCredentials(String username, String authCode) {
        @Override
        public String toString() {
            return "MailCredentials[username=<redacted>, authCode=<redacted>]";
        }
    }

    record OssConfig(String endpoint, String bucket, String accessKeyId, String accessKeySecret) {
        public boolean complete() {
            return endpoint != null && !endpoint.isBlank()
                    && bucket != null && !bucket.isBlank()
                    && accessKeyId != null && !accessKeyId.isBlank()
                    && accessKeySecret != null && !accessKeySecret.isBlank();
        }

        @Override
        public String toString() {
            return "OssConfig[endpoint=<redacted>, bucket=<redacted>, accessKeyId=<redacted>, accessKeySecret=<redacted>]";
        }
    }
}
