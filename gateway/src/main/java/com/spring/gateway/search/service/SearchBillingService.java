package com.spring.gateway.search.service;

import com.spring.gateway.billing.entity.PointsHold;
import com.spring.gateway.search.dto.SearchUsageEvent;

/**
 * 搜索计费业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface SearchBillingService {
    void chargeLocal(SearchUsageEvent event);

    void chargeNative(PointsHold hold, Long webSearches);
}
