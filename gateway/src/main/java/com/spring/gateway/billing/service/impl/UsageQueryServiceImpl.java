package com.spring.gateway.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.billing.dto.UsageRecordView;
import com.spring.gateway.billing.entity.UsageRecord;
import com.spring.gateway.billing.mapper.UsageRecordMapper;
import com.spring.gateway.billing.service.UsageQueryService;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 模型用量查询实现。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Service
@RequiredArgsConstructor
public class UsageQueryServiceImpl implements UsageQueryService {
    private final UsageRecordMapper usageRecordMapper;

    @Override
    public PageResult<Map<String, Object>> summarize(Long userId, String from, String to, PageQuery query) {
        return PageResult.of(usageRecordMapper.sumByUser(query.toPage(), userId, from, to));
    }

    @Override
    public PageResult<UsageRecordView> pageForAdmin(Long userId, String from, String to, PageQuery query) {
        return PageResult.of(usageRecordMapper.selectPage(query.toPage(), filter(userId, from, to)))
                .map(UsageRecordView::of);
    }

    @Override
    public PageResult<UsageRecordView> pageForUser(long userId, String from, String to, PageQuery query) {
        return PageResult.of(usageRecordMapper.selectPage(query.toPage(), filter(userId, from, to)))
                .map(UsageRecordView::forUser);
    }

    private static LambdaQueryWrapper<UsageRecord> filter(Long userId, String from, String to) {
        return new LambdaQueryWrapper<UsageRecord>()
                .eq(userId != null, UsageRecord::getUserId, userId)
                .ge(from != null && !from.isBlank(), UsageRecord::getCreatedAt, from)
                .lt(to != null && !to.isBlank(), UsageRecord::getCreatedAt, to)
                .orderByDesc(UsageRecord::getCreatedAt)
                .orderByDesc(UsageRecord::getId);
    }
}
