package com.spring.gateway.billing.controller;

import com.spring.gateway.billing.dto.PointsHoldView;
import com.spring.gateway.billing.dto.PointsLedgerView;
import com.spring.gateway.billing.service.PointsQueryService;
import com.spring.gateway.common.Result;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分对账查询接口。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@RestController
@RequestMapping("/api/admin/points")
@RequiredArgsConstructor
public class AdminPointsController {
    private final PointsQueryService pointsQueryService;

    /**
     * 积分流水。
     *
     * @param userId 用户 ID，可为空
     * @param page 页码
     * @param size 每页条数
     * @return 分页流水
     */
    @GetMapping("/ledger")
    public Result<PageResult<PointsLedgerView>> ledger(@RequestParam(required = false) Long userId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        return Result.ok(pointsQueryService.pageLedger(userId, new PageQuery(page, size)));
    }

    /**
     * 预扣单查询。
     *
     * @param status 状态过滤值
     * @return 预扣列表
     */
    @GetMapping("/holds")
    public Result<List<PointsHoldView>> holds(@RequestParam(defaultValue = "HELD") String status) {
        return Result.ok(pointsQueryService.holds(status));
    }
}
