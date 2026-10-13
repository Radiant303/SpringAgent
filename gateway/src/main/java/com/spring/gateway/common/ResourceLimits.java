package com.spring.gateway.common;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 主动额度变更的数值限制。
 *
 * @author hanbing
 * @since 2026-10-13
 */
public final class ResourceLimits {
    public static final int POINTS_SCALE = 6;
    public static final String MAX_POINTS_TEXT = "1000000";
    public static final String MIN_ADJUST_TEXT = "-1000000";
    public static final BigDecimal MAX_POINTS = new BigDecimal(MAX_POINTS_TEXT);
    public static final BigDecimal MAX_BALANCE = new BigDecimal("1000000000");
    public static final long MB = 1024L * 1024L;
    public static final long MAX_QUOTA_MB = 1_048_576L;
    public static final long MAX_QUOTA_BYTES = MAX_QUOTA_MB * MB;
    public static final int MAX_REDEEM_COUNT = 100;
    public static final long MAX_EXPIRES_HOURS = 87_600L;
    public static final long MAX_BILLING_TOKENS = 10_000_000L;

    private ResourceLimits() {
    }

    public static void points(BigDecimal value, boolean signed, String label) {
        if (value == null || value.scale() > POINTS_SCALE || value.abs().compareTo(MAX_POINTS) > 0
                || (!signed && value.signum() < 0) || (signed && value.signum() == 0)) {
            throw new BizException(400, label + (signed ? "须为非零数，绝对值不超过 " : "须在 0 到 ")
                    + MAX_POINTS_TEXT + "，最多 " + POINTS_SCALE + " 位小数");
        }
    }

    public static long integer(Long value, long min, long max, String label) {
        if (value == null || value < min || value > max) {
            throw new BizException(400, label + "必须是 " + min + " 到 " + max + " 的整数");
        }
        return value;
    }

    public static void activeBalance(BigDecimal balance) {
        if (balance.compareTo(MAX_BALANCE) > 0) {
            throw new BizException(409, "变更后积分余额不能超过 " + MAX_BALANCE.toPlainString());
        }
    }

    public static Map<String, Object> view() {
        return Map.of("pointsScale", POINTS_SCALE, "pointsMax", MAX_POINTS_TEXT,
                "balanceMax", MAX_BALANCE.toPlainString(), "quotaMbMax", MAX_QUOTA_MB,
                "quotaBytesMax", MAX_QUOTA_BYTES, "mbBytes", MB, "redeemCountMax", MAX_REDEEM_COUNT,
                "expiresHoursMax", MAX_EXPIRES_HOURS, "billingTokensMax", MAX_BILLING_TOKENS);
    }
}
