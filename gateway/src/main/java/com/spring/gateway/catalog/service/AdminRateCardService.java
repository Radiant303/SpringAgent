package com.spring.gateway.catalog.service;

import com.spring.gateway.catalog.dto.AdminRateCardRequest;
import com.spring.gateway.catalog.entity.RateCard;

import java.util.List;

/**
 * 费率管理业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface AdminRateCardService {
    List<RateCard> list();

    RateCard save(AdminRateCardRequest req);

    void delete(String modelId);
}
