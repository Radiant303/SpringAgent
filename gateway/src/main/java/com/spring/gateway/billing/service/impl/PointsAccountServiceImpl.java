package com.spring.gateway.billing.service.impl;

import com.spring.gateway.billing.entity.PointsLedger;
import com.spring.gateway.billing.mapper.PointsLedgerMapper;
import com.spring.gateway.billing.service.PointsAccountService;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.ResourceLimits;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.identity.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 积分账户业务实现。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Service
@RequiredArgsConstructor
public class PointsAccountServiceImpl implements PointsAccountService {
    private final UserMapper userMapper;
    private final PointsLedgerMapper pointsLedgerMapper;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    @Transactional
    public void adjust(long operatorId, long targetId, BigDecimal delta, String reason) {
        ResourceLimits.points(delta, true, "调账金额");
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new BizException(400, "事由必须为 1 到 255 个字符，不能全为空白");
        }
        BigDecimal after = applyDelta(targetId, delta, false);
        insertLedger(targetId, delta, after, PointsLedger.TYPE_ADJUST,
                String.valueOf(idGenerator.nextId()), null, operatorId, reason);
    }

    @Override
    public BigDecimal balanceOf(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return user.getPointsBalance() == null ? BigDecimal.ZERO : user.getPointsBalance();
    }

    @Override
    @Transactional
    public BigDecimal grantRedeem(long userId, BigDecimal points, String refId) {
        ResourceLimits.points(points, false, "兑换积分");
        if (points.signum() == 0) {
            throw new BizException(400, "兑换积分必须大于 0");
        }
        BigDecimal after = applyDelta(userId, points, false);
        insertLedger(userId, points, after, PointsLedger.TYPE_REDEEM, refId, null, null, null);
        return after;
    }

    private BigDecimal lockBalance(long userId) {
        BigDecimal balance = userMapper.selectBalanceForUpdate(userId);
        if (balance == null) {
            throw new BizException(404, "用户不存在");
        }
        return balance;
    }

    private BigDecimal applyDelta(long userId, BigDecimal delta, boolean allowNegative) {
        BigDecimal balance = lockBalance(userId);
        BigDecimal after = balance.add(delta);
        if (!allowNegative && after.signum() < 0) {
            throw new BizException(409, "积分不足：当前 " + balance.stripTrailingZeros().toPlainString()
                    + "，本次需 " + delta.negate().stripTrailingZeros().toPlainString());
        }
        ResourceLimits.activeBalance(after);
        userMapper.updateBalance(userId, after);
        return after;
    }

    private void insertLedger(long userId, BigDecimal change, BigDecimal after, String type,
                              String refId, String modelId, Long operatorId, String reason) {
        PointsLedger row = new PointsLedger();
        row.setId(idGenerator.nextId());
        row.setUserId(userId);
        row.setChangeAmount(change);
        row.setBalanceAfter(after);
        row.setType(type);
        row.setRefId(refId);
        row.setModelId(modelId);
        row.setOperatorId(operatorId);
        row.setReason(reason);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        pointsLedgerMapper.insert(row);
    }
}
