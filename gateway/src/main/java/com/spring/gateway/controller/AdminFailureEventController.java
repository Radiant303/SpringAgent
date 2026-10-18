package com.spring.gateway.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.RequiresOwner;
import com.spring.gateway.common.Result;
import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import com.spring.gateway.entity.SearchBillingFailure;
import com.spring.gateway.mapper.SearchBillingFailureMapper;
import com.spring.gateway.service.SearchBillingFailureService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 站长搜索计费失败事件接口。
 *
 * @author hanbing
 * @since 2026-10-18
 */
@RestController
@RequestMapping("/api/admin/failure-events")
@RequiredArgsConstructor
public class AdminFailureEventController {
    private final SearchBillingFailureMapper mapper;
    private final SearchBillingFailureService failures;

    @GetMapping
    @RequiresOwner
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status, @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String eventId) {
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
                .orderByDesc(SearchBillingFailure::getId);
        return Result.ok(PageResult.of(mapper.selectPage(query.toPage(), filter)).map(row -> view(row, false)));
    }

    @GetMapping("/{id}")
    @RequiresOwner
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        SearchBillingFailure row = mapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "失败事件不存在");
        }
        return Result.ok(view(row, true));
    }

    @PostMapping("/{id}/status")
    @RequiresOwner
    public Result<Void> status(@PathVariable Long id, @RequestBody StatusRequest request,
                              @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long operatorId) {
        failures.changeStatus(id, request.status(), request.remark(), operatorId);
        return Result.ok(null);
    }

    static Map<String, Object> view(SearchBillingFailure row, boolean detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(row.getId()));
        result.put("eventId", row.getEventId());
        result.put("provider", row.getProvider());
        result.put("userId", row.getUserId() == null ? null : row.getUserId().toString());
        result.put("sessionId", row.getSessionId());
        result.put("turnId", row.getTurnId());
        result.put("searches", row.getSearches());
        result.put("unitPoints", row.getUnitPoints());
        result.put("costPoints", row.getCostPoints());
        result.put("pointsReference", !"SUCCEEDED".equals(row.getStatus()));
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
        }
        return result;
    }

    /**
     * 失败事件人工标记参数。
     *
     * @author hanbing
     * @since 2026-10-18
     */
    public record StatusRequest(String status, String remark) {}
}
