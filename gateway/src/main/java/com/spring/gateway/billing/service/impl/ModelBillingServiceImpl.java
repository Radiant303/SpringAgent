package com.spring.gateway.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.billing.dto.ReserveModelRequest;
import com.spring.gateway.billing.dto.SettleModelRequest;
import com.spring.gateway.catalog.entity.ModelDefinition;
import com.spring.gateway.billing.entity.PointsHold;
import com.spring.gateway.billing.entity.PointsLedger;
import com.spring.gateway.catalog.entity.RateCard;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.billing.entity.UsageRecord;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.catalog.mapper.ModelDefinitionMapper;
import com.spring.gateway.billing.mapper.PointsHoldMapper;
import com.spring.gateway.billing.mapper.PointsLedgerMapper;
import com.spring.gateway.catalog.mapper.RateCardMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.billing.mapper.UsageRecordMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.billing.service.ModelBillingService;
import com.spring.gateway.search.service.SearchBillingService;
import com.spring.gateway.settings.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * 模型请求计费实现。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Service
@RequiredArgsConstructor
public class ModelBillingServiceImpl implements ModelBillingService {
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    /** 金额精度。 */
    private static final int SCALE = 6;
    private static final BigDecimal MIN_RESERVATION = new BigDecimal("0.000001");

    private final UserMapper userMapper;
    private final RateCardMapper rateCardMapper;
    private final PointsHoldMapper pointsHoldMapper;
    private final PointsLedgerMapper pointsLedgerMapper;
    private final UsageRecordMapper usageRecordMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final SystemSettingService systemSettingService;
    private final SessionMapper sessionMapper;
    private final ModelDefinitionMapper modelDefinitionMapper;
    private final SearchBillingService searchBillingService;

    @Transactional
    public BigDecimal reserveRequest(ReserveModelRequest request) {
        BigDecimal balance = lockBalance(request.userId());
        PointsHold existing = pointsHoldMapper.selectById(request.requestId());
        if (existing != null) {
            if (!Objects.equals(existing.getUserId(), request.userId())
                    || !Objects.equals(existing.getSessionId(), request.sessionId())
                    || !Objects.equals(existing.getTurnId(), request.turnId())
                    || !Objects.equals(existing.getModelId(), request.modelId())
                    || !Objects.equals(existing.getAgentName(), request.agentName())) {
                throw new BizException(409, "请求单号归属冲突");
            }
            if (!PointsHold.STATUS_HELD.equals(existing.getStatus())) {
                throw new BizException(409, "模型请求已经收口，不可再次发起");
            }
            return existing.getAmount();
        }
        User user = userMapper.selectById(request.userId());
        if (!User.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BizException(403, "用户已停用");
        }
        Session session = sessionMapper.selectById(request.sessionId());
        if (session == null || !Objects.equals(session.getUserId(), request.userId())
                || !Session.STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BizException(404, "会话不存在");
        }
        ModelDefinition model = modelDefinitionMapper.selectById(request.modelId());
        if (model == null || !Boolean.TRUE.equals(model.getEnabled())) {
            throw new BizException(400, "模型不存在或未启用");
        }
        RateCard rate = findRate(request.modelId());
        PointsHold hold = new PointsHold();
        hold.setRequestId(request.requestId());
        hold.setUserId(request.userId());
        hold.setTurnId(request.turnId());
        hold.setSessionId(request.sessionId());
        hold.setModelId(request.modelId());
        hold.setAgentName(request.agentName());
        hold.setInputPoints(rate == null ? BigDecimal.ZERO : rate.getInputPoints());
        hold.setCacheReadPoints(rate == null ? BigDecimal.ZERO : rate.getCacheReadPoints());
        hold.setCacheWritePoints(rate == null ? BigDecimal.ZERO : rate.getCacheWritePoints());
        hold.setOutputPoints(rate == null ? BigDecimal.ZERO : rate.getOutputPoints());
        BigDecimal amount = costOf(hold,
                systemSettingService.getLong(SystemSetting.KEY_BILLING_EST_INPUT_TOKENS, 10000L),
                systemSettingService.getLong(SystemSetting.KEY_BILLING_EST_CACHE_READ_TOKENS, 90000L),
                0L, systemSettingService.getLong(SystemSetting.KEY_BILLING_EST_OUTPUT_TOKENS, 20000L));
        if (hold.getInputPoints().signum() > 0 || hold.getOutputPoints().signum() > 0
                || hold.getCacheReadPoints().signum() > 0 || hold.getCacheWritePoints().signum() > 0) {
            amount = amount.max(MIN_RESERVATION);
        }
        if (amount.signum() > 0 && balance.compareTo(amount) < 0) {
            throw new BizException(409, "积分不足：当前 " + balance.stripTrailingZeros().toPlainString()
                    + "，本次请求需冻结 " + amount.stripTrailingZeros().toPlainString());
        }
        hold.setAmount(amount);
        hold.setStatus(PointsHold.STATUS_HELD);
        hold.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        pointsHoldMapper.insert(hold);
        if (amount.signum() > 0) {
            BigDecimal after = balance.subtract(amount);
            userMapper.updateBalance(request.userId(), after);
            insertLedger(request.userId(), amount.negate(), after, PointsLedger.TYPE_HOLD,
                    request.requestId(), request.modelId(), null, null);
        }
        return amount;
    }

    @Transactional
    public String settleRequest(SettleModelRequest request) {
        BigDecimal balance = lockBalance(request.userId());
        PointsHold hold = pointsHoldMapper.selectById(request.requestId());
        if (hold == null || !Objects.equals(hold.getUserId(), request.userId())) {
            throw new BizException(404, "计费请求不存在");
        }
        String status = settleTokens(request, hold, balance);
        searchBillingService.chargeNative(hold, request.webSearches());
        return status;
    }

    private String settleTokens(SettleModelRequest request, PointsHold hold, BigDecimal balance) {
        if (request.inputTokens() < 0 || request.cacheReadTokens() < 0
                || request.cacheWriteTokens() < 0 || request.outputTokens() < 0
                || request.cacheReadTokens() > request.inputTokens()
                || request.cacheWriteTokens() > request.inputTokens() - request.cacheReadTokens()) {
            throw new BizException(400, "Token 用量非法：缓存用量不能超过总输入");
        }
        UsageRecord previous = usageRecordMapper.selectOne(new LambdaQueryWrapper<UsageRecord>()
                .eq(UsageRecord::getRequestId, request.requestId()));
        if (previous != null && Boolean.TRUE.equals(previous.getUsageKnown())) {
            if (!sameUsage(previous, request)) {
                throw new BizException(409, "同一模型请求的结算数据不一致");
            }
            return hold.getStatus();
        }
        if (previous != null && !request.usageKnown()) {
            if (!sameUsage(previous, request)) {
                throw new BizException(409, "同一模型请求的结算数据不一致");
            }
            return hold.getStatus();
        }
        if (!List.of(PointsHold.STATUS_HELD, PointsHold.STATUS_UNKNOWN, PointsHold.STATUS_STALE)
                .contains(hold.getStatus())) {
            throw new BizException(409, "计费请求已经收口");
        }
        UsageRecord record = previous == null ? new UsageRecord() : previous;
        record.setRequestId(hold.getRequestId());
        record.setTurnId(hold.getTurnId());
        record.setSessionId(hold.getSessionId());
        record.setUserId(hold.getUserId());
        record.setModelId(hold.getModelId());
        String modelName = hold.getModelId();
        if (modelName != null) {
            ModelDefinition model = modelDefinitionMapper.selectById(modelName);
            if (model != null && model.getDisplayName() != null) {
                modelName = model.getDisplayName();
            }
        }
        record.setModelName(modelName == null ? "" : modelName);
        record.setAgentName(hold.getAgentName());
        record.setRequests(1);
        record.setInputTokens(request.inputTokens());
        record.setCacheReadTokens(request.cacheReadTokens());
        record.setCacheWriteTokens(request.cacheWriteTokens());
        record.setOutputTokens(request.outputTokens());
        record.setStatus(request.status());
        record.setUsageKnown(request.usageKnown());
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (request.usageKnown()) {
            long uncached = request.inputTokens() - request.cacheReadTokens() - request.cacheWriteTokens();
            BigDecimal cost = costOf(hold, uncached, request.cacheReadTokens(),
                    request.cacheWriteTokens(), request.outputTokens());
            record.setCostPoints(cost);
            BigDecimal change = hold.getAmount().subtract(cost);
            if (change.signum() != 0) {
                BigDecimal after = balance.add(change);
                userMapper.updateBalance(request.userId(), after);
                insertLedger(request.userId(), change, after, PointsLedger.TYPE_SETTLE,
                        request.requestId(), hold.getModelId(), null, null);
            }
            hold.setStatus(PointsHold.STATUS_SETTLED);
            hold.setSettledAt(LocalDateTime.now(ZoneOffset.UTC));
        } else {
            hold.setStatus(PointsHold.STATUS_UNKNOWN);
        }
        pointsHoldMapper.updateById(hold);
        if (previous == null) {
            usageRecordMapper.insert(record);
        } else {
            usageRecordMapper.updateById(record);
        }
        return hold.getStatus();
    }

    private static boolean sameUsage(UsageRecord record, SettleModelRequest request) {
        return Objects.equals(record.getStatus(), request.status())
                && Objects.equals(record.getUsageKnown(), request.usageKnown())
                && Objects.equals(record.getInputTokens(), request.inputTokens())
                && Objects.equals(record.getCacheReadTokens(), request.cacheReadTokens())
                && Objects.equals(record.getCacheWriteTokens(), request.cacheWriteTokens())
                && Objects.equals(record.getOutputTokens(), request.outputTokens());
    }

    @Transactional
    public void markStale(String requestId) {
        pointsHoldMapper.markStale(requestId);
    }

    private RateCard findRate(String modelId) {
        RateCard row = rateCardMapper.selectById(modelId);
        if (row != null && Boolean.TRUE.equals(row.getEnabled())) {
            return row;
        }
        RateCard fallback = rateCardMapper.selectById(RateCard.DEFAULT_MODEL_ID);
        return fallback != null && Boolean.TRUE.equals(fallback.getEnabled()) ? fallback : null;
    }

    private static BigDecimal costOf(PointsHold rate, long uncached, long cacheRead,
                                     long cacheWrite, long output) {
        BigDecimal total = rate.getInputPoints().multiply(BigDecimal.valueOf(uncached))
                .add(rate.getCacheReadPoints().multiply(BigDecimal.valueOf(cacheRead)))
                .add(rate.getCacheWritePoints().multiply(BigDecimal.valueOf(cacheWrite)))
                .add(rate.getOutputPoints().multiply(BigDecimal.valueOf(output)));
        return total.divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal lockBalance(long userId) {
        BigDecimal balance = userMapper.selectBalanceForUpdate(userId);
        if (balance == null) {
            throw new BizException(404, "用户不存在");
        }
        return balance;
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
