package com.spring.gateway.billing.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.spring.gateway.billing.mapper.UsageRecordMapper;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型用量查询单元测试。
 *
 * @author hanbing
 * @since 2026-11-12
 */
class UsageQueryServiceUnitTests {
    private final UsageRecordMapper mapper = mock(UsageRecordMapper.class);
    private final UsageQueryServiceImpl service = new UsageQueryServiceImpl(mapper);

    @Test
    void summaryUsesDedicatedCountWithoutAutomaticCount() {
        String from = "2026-11-01";
        String to = "2026-11-12";
        when(mapper.countByUser(isNull(), eq(from), eq(to))).thenReturn(21L);
        when(mapper.sumByUser(ArgumentMatchers.<Page<Map<String, Object>>>any(),
                isNull(), eq(from), eq(to))).thenAnswer(invocation -> {
            Page<Map<String, Object>> page = invocation.getArgument(0);
            assertFalse(page.searchCount());
            assertEquals(21L, page.getTotal());
            page.setRecords(List.of(Map.of("modelId", "model-1")));
            return page;
        });

        PageResult<Map<String, Object>> result = service.summarize(
                null, from, to, new PageQuery(2, 10));

        assertEquals(21L, result.total());
        assertEquals(2L, result.page());
        assertEquals(10L, result.size());
        assertEquals(1, result.list().size());
        verify(mapper).countByUser(null, from, to);
    }
}
