package com.spring.gateway.billing.controller;

import com.spring.gateway.billing.dto.UsageRecordView;
import com.spring.gateway.billing.service.UsageQueryService;
import com.spring.gateway.common.Result;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端模型用量查询接口。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
public class AdminUsageController {
    private final UsageQueryService usageQueryService;

    /**
     * 按用户与模型统计用量。
     *
     * @param userId 用户 ID，可为空
     * @param from 起始时间（含）
     * @param to 截止时间（不含）
     * @param page 页码
     * @param size 每页条数
     * @return 分页聚合行
     */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> usage(@RequestParam(required = false) Long userId,
                                                         @RequestParam(required = false) String from,
                                                         @RequestParam(required = false) String to,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
        return Result.ok(usageQueryService.summarize(userId, from, to, new PageQuery(page, size)));
    }

    /**
     * 模型请求用量明细。
     *
     * @param userId 用户 ID，可为空
     * @param from 起始时间（含）
     * @param to 截止时间（不含）
     * @param page 页码
     * @param size 每页条数
     * @return 分页明细
     */
    @GetMapping("/records")
    public Result<PageResult<UsageRecordView>> records(@RequestParam(required = false) Long userId,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        return Result.ok(usageQueryService.pageForAdmin(userId, from, to, new PageQuery(page, size)));
    }
}
