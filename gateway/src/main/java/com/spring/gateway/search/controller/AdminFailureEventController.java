package com.spring.gateway.search.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.RequiresOwner;
import com.spring.gateway.common.Result;
import com.spring.gateway.dto.PageResult;
import com.spring.gateway.search.service.FailureEventQueryService;
import com.spring.gateway.search.service.SearchBillingFailureService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站长计费失败事件接口。
 *
 * @author hanbing
 * @since 2026-10-18
 */
@RestController
@RequestMapping("/api/admin/failure-events")
@RequiredArgsConstructor
public class AdminFailureEventController {
    private final FailureEventQueryService queries;
    private final SearchBillingFailureService failures;

    /**
     * 失败事件分页。
     *
     * @return 失败事件分页视图
     */
    @GetMapping
    @RequiresOwner
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status, @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) String failureType) {
        return Result.ok(queries.page(page, size, status, userId, eventId, failureType));
    }

    /**
     * 失败事件详情。
     *
     * @param id 事件 ID
     * @return 事件详情
     */
    @GetMapping("/{id}")
    @RequiresOwner
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(queries.detail(id));
    }

    /**
     * 人工修改失败事件状态。
     *
     * @param id 事件 ID
     * @param request 状态与备注
     * @param operatorId 操作者 ID
     * @return 空数据返回体
     */
    @PostMapping("/{id}/status")
    @RequiresOwner
    public Result<Void> status(@PathVariable Long id, @RequestBody StatusRequest request,
                               @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long operatorId) {
        failures.changeStatus(id, request.status(), request.remark(), operatorId);
        return Result.ok(null);
    }

    /**
     * 失败事件人工标记参数。
     *
     * @author hanbing
     * @since 2026-10-18
     */
    public record StatusRequest(String status, String remark) {
    }
}
