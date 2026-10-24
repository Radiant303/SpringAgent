package com.spring.gateway.billing.service;

import com.spring.gateway.billing.dto.ModelBillingFailureRequest;

/**
 * 模型结算失败上报业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface ModelBillingFailureService {
    String report(ModelBillingFailureRequest request);
}
