package com.spring.gateway.billing.service;

import com.spring.gateway.billing.dto.ReserveModelRequest;
import com.spring.gateway.billing.dto.SettleModelRequest;

import java.math.BigDecimal;

/**
 * 模型请求计费业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface ModelBillingService {
    BigDecimal reserveRequest(ReserveModelRequest request);

    String settleRequest(SettleModelRequest request);

    void markStale(String requestId);
}
