package com.spring.gateway.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.billing.dto.PointsHoldView;
import com.spring.gateway.billing.dto.PointsLedgerView;
import com.spring.gateway.billing.entity.PointsHold;
import com.spring.gateway.billing.entity.PointsLedger;
import com.spring.gateway.billing.mapper.PointsHoldMapper;
import com.spring.gateway.billing.mapper.PointsLedgerMapper;
import com.spring.gateway.billing.service.PointsQueryService;
import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 积分账目查询实现。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Service
@RequiredArgsConstructor
public class PointsQueryServiceImpl implements PointsQueryService {
    private static final int HOLD_LIMIT_MAX = 500;

    private final PointsLedgerMapper pointsLedgerMapper;
    private final PointsHoldMapper pointsHoldMapper;

    @Override
    public List<PointsLedgerView> recentLedger(long userId, int limit) {
        return pointsLedgerMapper.selectList(new LambdaQueryWrapper<PointsLedger>()
                        .eq(PointsLedger::getUserId, userId).orderByDesc(PointsLedger::getId).last("LIMIT " + limit))
                .stream().map(PointsQueryServiceImpl::toLedgerView).toList();
    }

    @Override
    public PageResult<PointsLedgerView> pageLedger(Long userId, PageQuery query) {
        return PageResult.of(pointsLedgerMapper.selectPage(query.toPage(),
                        new LambdaQueryWrapper<PointsLedger>()
                                .eq(userId != null, PointsLedger::getUserId, userId)
                                .orderByDesc(PointsLedger::getId)))
                .map(PointsQueryServiceImpl::toLedgerView);
    }

    @Override
    public List<PointsHoldView> holds(String status) {
        return pointsHoldMapper.selectList(new LambdaQueryWrapper<PointsHold>()
                        .eq(PointsHold::getStatus, status)
                        .orderByDesc(PointsHold::getCreatedAt)
                        .last("LIMIT " + HOLD_LIMIT_MAX))
                .stream().map(PointsQueryServiceImpl::toHoldView).toList();
    }

    private static PointsLedgerView toLedgerView(PointsLedger row) {
        return new PointsLedgerView(
                String.valueOf(row.getId()),
                String.valueOf(row.getUserId()),
                row.getChangeAmount(),
                row.getBalanceAfter(),
                row.getType(),
                row.getRefId(),
                row.getModelId(),
                row.getOperatorId() == null ? null : String.valueOf(row.getOperatorId()),
                row.getReason(),
                TimeFormat.isoUtc(row.getCreatedAt()));
    }

    private static PointsHoldView toHoldView(PointsHold row) {
        return new PointsHoldView(
                row.getTurnId(),
                String.valueOf(row.getUserId()),
                row.getAmount(),
                row.getStatus(),
                TimeFormat.isoUtc(row.getCreatedAt()),
                row.getSettledAt() == null ? null : TimeFormat.isoUtc(row.getSettledAt()));
    }
}
