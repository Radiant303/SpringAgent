package com.spring.gateway.billing.service;

import com.spring.gateway.billing.dto.UsageRecordView;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;

import java.util.Map;

/**
 * 模型用量查询业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface UsageQueryService {
    PageResult<Map<String, Object>> summarize(Long userId, String from, String to, PageQuery query);

    PageResult<UsageRecordView> pageForAdmin(Long userId, String from, String to, PageQuery query);

    PageResult<UsageRecordView> pageForUser(long userId, String from, String to, PageQuery query);
}
