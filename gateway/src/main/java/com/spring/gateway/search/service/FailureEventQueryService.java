package com.spring.gateway.search.service;

import com.spring.gateway.dto.PageResult;

import java.util.Map;

/**
 * 计费失败事件查询业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface FailureEventQueryService {
    PageResult<Map<String, Object>> page(int page, int size, String status, Long userId,
                                         String eventId, String failureType);

    Map<String, Object> detail(long id);
}
