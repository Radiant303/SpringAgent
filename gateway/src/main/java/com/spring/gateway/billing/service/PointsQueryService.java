package com.spring.gateway.billing.service;

import com.spring.gateway.billing.dto.PointsHoldView;
import com.spring.gateway.billing.dto.PointsLedgerView;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;

import java.util.List;

/**
 * 积分账目查询业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface PointsQueryService {
    List<PointsLedgerView> recentLedger(long userId, int limit);

    PageResult<PointsLedgerView> pageLedger(Long userId, PageQuery query);

    List<PointsHoldView> holds(String status);
}
