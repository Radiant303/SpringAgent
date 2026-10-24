package com.spring.gateway.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.billing.dto.ModelBillingFailureRequest;
import com.spring.gateway.billing.dto.SettleModelRequest;
import com.spring.gateway.billing.entity.PointsHold;
import com.spring.gateway.search.entity.SearchBillingFailure;
import com.spring.gateway.billing.entity.UsageRecord;
import com.spring.gateway.billing.mapper.PointsHoldMapper;
import com.spring.gateway.search.mapper.SearchBillingFailureMapper;
import com.spring.gateway.billing.mapper.UsageRecordMapper;
import com.spring.gateway.search.mapper.SearchUsageRecordMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.billing.service.ModelBillingFailureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * 模型结算投递审计。
 *
 * @author hanbing
 * @since 2026-10-19
 */
@Service
@RequiredArgsConstructor
public class ModelBillingFailureServiceImpl implements ModelBillingFailureService {
    private final SearchBillingFailureMapper failures;
    private final PointsHoldMapper holds;
    private final UsageRecordMapper usageRecords;
    private final UserMapper users;
    private final SearchUsageRecordMapper searchUsageRecords;
    private final SnowflakeIdGenerator ids;
    private final JsonMapper json;

    @Transactional
    public String report(ModelBillingFailureRequest request) {
        SettleModelRequest settlement = request.settlement();
        if (!Objects.equals(request.requestId(), settlement.requestId())
                || !Objects.equals(request.userId(), settlement.userId())) {
            throw new BizException(409, "结算上报请求归属冲突");
        }
        LocalDateTime first = utc(request.firstFailedAt());
        LocalDateTime last = utc(request.lastFailedAt());
        if (first.isAfter(last)) {
            throw new BizException(400, "失败时间顺序非法");
        }
        users.selectBalanceForUpdate(request.userId());
        PointsHold hold = holds.selectOne(new LambdaQueryWrapper<PointsHold>()
                .eq(PointsHold::getRequestId, request.requestId()).last("FOR UPDATE"));
        UsageRecord usage = usageRecords.selectOne(new LambdaQueryWrapper<UsageRecord>()
                .eq(UsageRecord::getRequestId, request.requestId()).last("FOR UPDATE"));
        if ((hold != null && !Objects.equals(hold.getUserId(), request.userId()))
                || (usage != null && !Objects.equals(usage.getUserId(), request.userId()))) {
            throw new BizException(409, "计费请求归属冲突");
        }
        boolean discarded = "DISCARDED".equals(request.deliveryStatus());
        if (discarded && (hold != null || usage != null || !zeroUsage(settlement))) {
            throw new BizException(409, "仅可清理不存在的零消费请求");
        }
        boolean succeeded = hold != null && PointsHold.STATUS_SETTLED.equals(hold.getStatus())
                && usage != null && Boolean.TRUE.equals(usage.getUsageKnown())
                && Objects.equals(usage.getRequestId(), settlement.requestId())
                && Objects.equals(usage.getModelId(), hold.getModelId())
                && Objects.equals(usage.getStatus(), settlement.status())
                && Objects.equals(usage.getUsageKnown(), settlement.usageKnown())
                && Objects.equals(usage.getInputTokens(), settlement.inputTokens())
                && Objects.equals(usage.getCacheReadTokens(), settlement.cacheReadTokens())
                && Objects.equals(usage.getCacheWriteTokens(), settlement.cacheWriteTokens())
                && Objects.equals(usage.getOutputTokens(), settlement.outputTokens())
                && nativeSettled(hold, settlement.webSearches());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SearchBillingFailure seed = new SearchBillingFailure();
        seed.setId(ids.nextId());
        seed.setDedupKey("MODEL_SETTLE:" + request.requestId());
        seed.setFailureType("MODEL_SETTLE");
        seed.setRequestId(request.requestId());
        seed.setUserId(request.userId());
        seed.setRawBody(json.writeValueAsString(settlement).getBytes(StandardCharsets.UTF_8));
        seed.setHeaders("{}");
        seed.setStatus("PENDING");
        seed.setRetryCount(request.failureCount());
        seed.setMaxRetries(null);
        seed.setErrorType(request.errorType());
        seed.setErrorMessage(request.errorMessage());
        seed.setCreatedAt(now);
        seed.setUpdatedAt(now);
        failures.insertOnce(seed);
        SearchBillingFailure row = failures.lockByKey(seed.getDedupKey());
        if (row == null) {
            throw new IllegalStateException("模型结算审计记录未创建");
        }
        if (!"MODEL_SETTLE".equals(row.getFailureType())
                || !Objects.equals(row.getRequestId(), request.requestId())
                || !Objects.equals(row.getUserId(), request.userId())) {
            throw new BizException(409, "失败记录归属冲突");
        }
        if ("SUCCEEDED".equals(row.getStatus())) {
            return row.getStatus();
        }
        boolean newer = row.getLastFailedAt() == null || last.isAfter(row.getLastFailedAt())
                || (last.equals(row.getLastFailedAt()) && request.failureCount() >= row.getRetryCount());
        if (newer) {
            if (!("DELIVERED".equals(row.getDeliveryStatus()) && "RETRYING".equals(request.deliveryStatus()))) {
                row.setRawBody(seed.getRawBody());
            }
            row.setErrorType(request.errorType());
            row.setErrorMessage(request.errorMessage());
            row.setLastFailedAt(last);
        }
        row.setFirstFailedAt(row.getFirstFailedAt() == null || first.isBefore(row.getFirstFailedAt())
                ? first : row.getFirstFailedAt());
        row.setRetryCount(Math.max(row.getRetryCount(), request.failureCount()));
        if (row.getDeliveryStatus() == null || "RETRYING".equals(row.getDeliveryStatus())
                || (newer && !"RETRYING".equals(request.deliveryStatus()))) {
            row.setDeliveryStatus(request.deliveryStatus());
        }
        if (!"SUCCEEDED".equals(row.getStatus())) {
            row.setSettlementStatus(hold == null ? (discarded ? "DISCARDED" : "MISSING") : hold.getStatus());
            row.setModelId(hold != null ? hold.getModelId() : usage == null ? null : usage.getModelId());
            row.setSessionId(hold == null ? null : hold.getSessionId());
            row.setTurnId(hold == null ? null : hold.getTurnId());
            if (succeeded) {
                row.setStatus("SUCCEEDED");
                row.setCostPoints(usage.getCostPoints());
                row.setCompletedAt(hold.getSettledAt() == null ? now : hold.getSettledAt());
            } else if (discarded) {
                row.setStatus("IGNORED");
                row.setCompletedAt(now);
            } else if (!"RESOLVED".equals(row.getStatus()) && !"IGNORED".equals(row.getStatus())) {
                row.setStatus("RETRYING".equals(row.getDeliveryStatus()) ? "RETRYING" : "PENDING");
            }
        }
        row.setMaxRetries(null);
        row.setUpdatedAt(now);
        failures.updateById(row);
        return row.getStatus();
    }

    private boolean nativeSettled(PointsHold hold, Long webSearches) {
        if (webSearches == null) {
            return true;
        }
        String eventId = "native:" + hold.getRequestId();
        var record = searchUsageRecords.selectForUpdate(eventId);
        return record != null && Objects.equals(record.getEventId(), eventId)
                && "native".equals(record.getSource()) && "native".equals(record.getProvider())
                && Objects.equals(record.getUserId(), hold.getUserId())
                && Objects.equals(record.getRequestId(), hold.getRequestId())
                && Objects.equals(record.getSessionId(), hold.getSessionId())
                && Objects.equals(record.getTurnId(), hold.getTurnId())
                && Objects.equals(record.getAgentName(), hold.getAgentName())
                && Objects.equals(record.getSearches(), webSearches)
                && record.getUnitPoints() != null && record.getUnitPoints().signum() >= 0
                && record.getCostPoints() != null
                && record.getCostPoints().compareTo(record.getUnitPoints()
                        .multiply(java.math.BigDecimal.valueOf(webSearches))) == 0;
    }

    private static boolean zeroUsage(SettleModelRequest request) {
        return Boolean.TRUE.equals(request.usageKnown()) && request.inputTokens() == 0
                && request.cacheReadTokens() == 0 && request.cacheWriteTokens() == 0
                && request.outputTokens() == 0 && (request.webSearches() == null || request.webSearches() == 0);
    }

    private static LocalDateTime utc(String value) {
        try {
            OffsetDateTime time = OffsetDateTime.parse(value);
            if (!ZoneOffset.UTC.equals(time.getOffset())) {
                throw new BizException(400, "失败时间必须为 UTC");
            }
            return time.toLocalDateTime();
        } catch (DateTimeParseException exception) {
            throw new BizException(400, "失败时间必须为 ISO UTC 时间");
        }
    }
}
