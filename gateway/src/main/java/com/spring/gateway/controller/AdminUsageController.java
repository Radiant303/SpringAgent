package com.spring.gateway.controller;

import com.spring.gateway.common.Result;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import com.spring.gateway.mapper.UsageRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用量统计接口：基于 usage_records 计费底账的聚合查询（管理后台）。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
public class AdminUsageController {

    private final UsageRecordMapper usageRecordMapper;

    /**
     * 按 用户 × 模型 分组的 token 消耗统计。
     *
     * @param userId 只看该用户；缺省为全部
     * @param from   起始时间（含），如 2026-10-01；缺省不限
     * @param to     截止时间（不含）；缺省不限
     * @param page   页码（1 起）
     * @param size   每页条数（上限 100）
     * @return 分页聚合行
     */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> usage(@RequestParam(required = false) Long userId,
                                                         @RequestParam(required = false) String from,
                                                         @RequestParam(required = false) String to,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
        PageQuery q = new PageQuery(page, size);
        return Result.ok(PageResult.of(usageRecordMapper.sumByUser(q.toPage(), userId, from, to)));
    }
}
