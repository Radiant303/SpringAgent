package com.spring.gateway.billing.service;

import java.math.BigDecimal;

/**
 * 积分账户业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface PointsAccountService {
    void adjust(long operatorId, long targetId, BigDecimal delta, String reason);

    BigDecimal balanceOf(long userId);

    BigDecimal grantRedeem(long userId, BigDecimal points, String refId);
}
