package com.spring.gateway.billing.controller;

import com.spring.gateway.billing.dto.PointsLedgerView;
import com.spring.gateway.billing.dto.UsageRecordView;
import com.spring.gateway.billing.service.PointsAccountService;
import com.spring.gateway.billing.service.PointsQueryService;
import com.spring.gateway.billing.service.UsageQueryService;
import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.Result;
import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import com.spring.gateway.redemption.dto.MyRedemptionView;
import com.spring.gateway.redemption.dto.RedeemRequest;
import com.spring.gateway.redemption.dto.RedeemResultView;
import com.spring.gateway.redemption.service.RedeemService;
import com.spring.gateway.workspace.service.WorkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户侧积分自查、存储概览与兑换码兑换。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {
    private static final int RECENT_LIMIT = 10;

    private final PointsAccountService pointsAccountService;
    private final PointsQueryService pointsQueryService;
    private final UsageQueryService usageQueryService;
    private final RedeemService redeemService;
    private final WorkService workService;

    /**
     * 当前用户的积分余额、最近流水与存储概览。
     *
     * @param userId 当前用户 ID
     * @return 余额、流水与存储概览
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        BigDecimal balance = pointsAccountService.balanceOf(userId);
        List<PointsLedgerView> ledger = pointsQueryService.recentLedger(userId, RECENT_LIMIT);
        WorkService.StorageOverview quota = workService.storageOverview(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("balance", balance);
        data.put("ledger", ledger);
        data.put("quota_bytes", quota.quotaBytes());
        data.put("storage_used_bytes", quota.usedBytes());
        data.put("work_quota_bytes", quota.workMaxBytes());
        return Result.ok(data);
    }

    /**
     * 当前用户的模型请求用量明细。
     *
     * @param userId 当前用户 ID
     * @param from 起始时间（含）
     * @param to 截止时间（不含）
     * @param page 页码
     * @param size 每页条数
     * @return 分页明细
     */
    @GetMapping("/usage")
    public Result<PageResult<UsageRecordView>> usage(
            @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(usageQueryService.pageForUser(userId, from, to, new PageQuery(page, size)));
    }

    /**
     * 当前用户的兑换记录。
     *
     * @param userId 当前用户 ID
     * @return 最近兑换记录
     */
    @GetMapping("/redemptions")
    public Result<List<MyRedemptionView>> redemptions(
            @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return Result.ok(redeemService.myRedemptions(userId, RECENT_LIMIT).stream()
                .map(row -> new MyRedemptionView(
                        row.getCode(),
                        row.getPointsAmount(),
                        row.getStorageDeltaBytes(),
                        row.getWorkQuotaDeltaBytes(),
                        TimeFormat.isoUtc(row.getRedeemedAt())))
                .toList());
    }

    /**
     * 兑换兑换码。
     *
     * @param userId 当前用户 ID
     * @param req 兑换码
     * @return 实发面值与入账后余额
     */
    @PostMapping("/redeem")
    public Result<RedeemResultView> redeem(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                           @Valid @RequestBody RedeemRequest req) {
        return Result.ok(redeemService.redeem(userId, req.code()));
    }
}
