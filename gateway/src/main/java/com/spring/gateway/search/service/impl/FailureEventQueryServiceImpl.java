package com.spring.gateway.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.billing.dto.SettleModelRequest;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import com.spring.gateway.search.entity.SearchBillingFailure;
import com.spring.gateway.search.mapper.SearchBillingFailureMapper;
import com.spring.gateway.search.service.FailureEventQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 计费失败事件查询实现。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Service
@RequiredArgsConstructor
public class FailureEventQueryServiceImpl implements FailureEventQueryService {
    private final SearchBillingFailureMapper mapper;

    @Override
    public PageResult<Map<String, Object>> page(int page, int size, String status, Long userId,
                                                String eventId, String failureType) {
        if (failureType != null && !failureType.isBlank()
                && !Set.of("SEARCH_BILLING", "MODEL_SETTLE").contains(failureType)) {
            throw new BizException(400, "失败事件类型非法");
        }
        if (status != null && !status.isBlank()
                && !Set.of("PENDING", "RETRYING", "SUCCEEDED", "RESOLVED", "IGNORED").contains(status)) {
            throw new BizException(400, "失败事件状态非法");
        }
        PageQuery query = new PageQuery(page, size);
        LambdaQueryWrapper<SearchBillingFailure> filter = new LambdaQueryWrapper<SearchBillingFailure>()
                .select(SearchBillingFailure.class, field -> !Set.of("rawBody", "headers").contains(field.getProperty()))
                .eq(status != null && !status.isBlank(), SearchBillingFailure::getStatus, status)
                .eq(userId != null, SearchBillingFailure::getUserId, userId)
                .eq(eventId != null && !eventId.isBlank(), SearchBillingFailure::getEventId, eventId)
                .eq(failureType != null && !failureType.isBlank(), SearchBillingFailure::getFailureType, failureType)
                .orderByDesc(SearchBillingFailure::getId);
        return PageResult.of(mapper.selectPage(query.toPage(), filter)).map(row -> view(row, false));
    }

    @Override
    public Map<String, Object> detail(long id) {
        SearchBillingFailure row = mapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "失败事件不存在");
        }
        return view(row, true);
    }

    static Map<String, Object> view(SearchBillingFailure row, boolean detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean model = "MODEL_SETTLE".equals(row.getFailureType());
        result.put("id", String.valueOf(row.getId()));
        result.put("failureType", row.getFailureType());
        result.put("requestId", row.getRequestId());
        result.put("modelId", row.getModelId());
        result.put("settlementStatus", row.getSettlementStatus());
        result.put("deliveryStatus", row.getDeliveryStatus());
        result.put("firstFailedAt", row.getFirstFailedAt() == null ? null : TimeFormat.isoUtc(row.getFirstFailedAt()));
        result.put("lastFailedAt", row.getLastFailedAt() == null ? null : TimeFormat.isoUtc(row.getLastFailedAt()));
        result.put("completedAt", row.getCompletedAt() == null ? null : TimeFormat.isoUtc(row.getCompletedAt()));
        result.put("eventId", row.getEventId());
        result.put("provider", row.getProvider());
        result.put("userId", row.getUserId() == null ? null : row.getUserId().toString());
        result.put("sessionId", row.getSessionId());
        result.put("turnId", row.getTurnId());
        result.put("searches", row.getSearches());
        result.put("unitPoints", row.getUnitPoints());
        result.put("costPoints", row.getCostPoints());
        result.put("pointsReference", model
                ? !("SETTLED".equals(row.getSettlementStatus()) && "SUCCEEDED".equals(row.getStatus()))
                : !"SUCCEEDED".equals(row.getStatus()));
        result.put("status", row.getStatus());
        result.put("retryCount", row.getRetryCount());
        result.put("maxRetries", row.getMaxRetries());
        result.put("nextRetryAt", row.getNextRetryAt() == null ? null : TimeFormat.isoUtc(row.getNextRetryAt()));
        result.put("errorType", row.getErrorType());
        String error = row.getErrorMessage();
        result.put("errorMessage", detail || error == null || error.length() <= 500 ? error : error.substring(0, 500));
        result.put("createdAt", TimeFormat.isoUtc(row.getCreatedAt()));
        result.put("updatedAt", TimeFormat.isoUtc(row.getUpdatedAt()));
        result.put("handledBy", row.getHandledBy() == null ? null : row.getHandledBy().toString());
        result.put("handledAt", row.getHandledAt() == null ? null : TimeFormat.isoUtc(row.getHandledAt()));
        result.put("remark", row.getRemark());
        if (detail) {
            result.put("rawBody", new String(row.getRawBody(), StandardCharsets.UTF_8));
            result.put("rawBodyBase64", Base64.getEncoder().encodeToString(row.getRawBody()));
            result.put("headers", row.getHeaders());
            if (model) {
                result.put("settlement", tools.jackson.databind.json.JsonMapper.builder().build()
                        .readValue(row.getRawBody(), SettleModelRequest.class));
            }
        }
        return result;
    }
}
