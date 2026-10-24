package com.spring.gateway.search.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.search.entity.SearchBillingFailure;
import com.spring.gateway.search.entity.SearchUsageRecord;
import com.spring.gateway.search.mapper.SearchBillingFailureMapper;
import com.spring.gateway.search.mapper.SearchUsageRecordMapper;
import com.spring.gateway.search.service.SearchBillingFailureService;
import com.spring.gateway.search.service.SearchBillingService;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 搜索计费自动重试与租约测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
class SearchBillingRetryTests {
    private final SearchBillingFailureMapper failures = mock(SearchBillingFailureMapper.class);
    private final SearchUsageRecordMapper usage = mock(SearchUsageRecordMapper.class);
    private final SearchBillingService billing = mock(SearchBillingService.class);
    private final SearchBillingRetryWorker worker = new SearchBillingRetryWorker(failures, usage, billing, JsonMapper.builder().build());

    @BeforeEach
    void init() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SearchBillingFailure.class);
    }

    private SearchBillingFailure row(int attempts) {
        SearchBillingFailure row = new SearchBillingFailure();
        row.setId(1L); row.setFailureType("SEARCH_BILLING");
        row.setStatus("RETRYING"); row.setLeaseToken("mine");
        row.setRetryCount(attempts); row.setMaxRetries(3);
        row.setRawBody("{\"eventId\":\"event\",\"source\":\"local\",\"provider\":\"exa\",\"searches\":1,\"userId\":7,\"sessionId\":\"session\",\"turnId\":\"turn\"}"
                .getBytes(StandardCharsets.UTF_8));
        when(failures.lockById(1L)).thenReturn(row);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assigned() {
        ArgumentCaptor<Wrapper<SearchBillingFailure>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(failures).update(isNull(), captor.capture());
        LambdaUpdateWrapper<SearchBillingFailure> update = (LambdaUpdateWrapper<SearchBillingFailure>) captor.getValue();
        String sql = update.getSqlSet();
        Map<String, Object> result = new java.util.HashMap<>();
        for (String assignment : sql.split(",")) {
            String[] pieces = assignment.split("=", 2);
            String key = pieces[1].substring(pieces[1].lastIndexOf('.') + 1, pieces[1].length() - 1);
            result.put(pieces[0], update.getParamNameValuePairs().get(key));
        }
        return result;
    }

    @Test
    void retryBackoffUsesFiveThenThirtySeconds() {
        row(1);
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        worker.failed(1L, "mine", new TransientDataAccessResourceException("offline"));
        Map<String, Object> first = assigned();
        assertEquals("RETRYING", first.get("status"));
        assertFalse(((LocalDateTime) first.get("next_retry_at")).isBefore(before.plusSeconds(5)));
        reset(failures);
        row(2);
        before = LocalDateTime.now(ZoneOffset.UTC);
        worker.failed(1L, "mine", new TransientDataAccessResourceException("offline"));
        assertFalse(((LocalDateTime) assigned().get("next_retry_at")).isBefore(before.plusSeconds(30)));
    }

    @Test
    void thirdFailureExhaustsBudgetWithoutReset() {
        row(3);
        worker.failed(1L, "mine", new TransientDataAccessResourceException("offline"));
        Map<String, Object> update = assigned();
        assertEquals("PENDING", update.get("status"));
        assertNull(update.get("next_retry_at"));
        assertNull(update.get("lease_token"));
        assertFalse(update.containsKey("retry_count"));
    }

    @Test
    void permanentFailureStopsImmediately() {
        row(1);
        worker.failed(1L, "mine", new BizException(409, "conflict"));
        assertEquals("PENDING", assigned().get("status"));
        verifyNoInteractions(billing);
    }

    @Test
    void staleLeaseCannotChargeOrFinishAnotherWorkersAttempt() {
        row(1).setLeaseToken("new-owner");
        worker.execute(1L, "mine");
        worker.failed(1L, "mine", new IllegalStateException());
        verifyNoInteractions(billing, usage);
        verify(failures, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void successUsesExistingIdempotentBillingAndActualSnapshot() {
        row(1);
        SearchUsageRecord actual = new SearchUsageRecord();
        actual.setUnitPoints(new BigDecimal("0.654321"));
        actual.setCostPoints(actual.getUnitPoints());
        when(usage.selectById("event")).thenReturn(actual);
        worker.execute(1L, "mine");
        verify(billing).chargeLocal(argThat(event -> event.eventId().equals("event")));
        Map<String, Object> update = assigned();
        assertEquals("SUCCEEDED", update.get("status"));
        assertEquals(actual.getCostPoints(), update.get("cost_points"));
        assertNull(update.get("lease_token"));
    }

    @Test
    void concurrentSchedulersExecuteOnlyAtomicClaimWinner() throws Exception {
        SearchBillingRetryWorker mockWorker = mock(SearchBillingRetryWorker.class);
        var ready = new java.util.concurrent.CyclicBarrier(2);
        var claimed = new java.util.concurrent.atomic.AtomicBoolean();
        when(failures.findDue(any())).thenAnswer(invocation -> {
            ready.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return List.of(1L);
        });
        when(failures.claim(eq(1L), anyString(), any(), any()))
                .thenAnswer(invocation -> claimed.compareAndSet(false, true) ? 1 : 0);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(new SearchBillingRetryTask(failures, mockWorker)::retryDue);
            var second = executor.submit(new SearchBillingRetryTask(failures, mockWorker)::retryDue);
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            verify(mockWorker, times(1)).execute(eq(1L), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void conditionalClaimsSkipConcurrentWinnerAndRecoverExpiredExhaustion() throws Exception {
        SearchBillingRetryWorker mockWorker = mock(SearchBillingRetryWorker.class);
        when(failures.findDue(any())).thenReturn(List.of(1L, 2L));
        when(failures.claim(eq(1L), anyString(), any(), any())).thenReturn(0);
        when(failures.claim(eq(2L), anyString(), any(), any())).thenReturn(1);
        new SearchBillingRetryTask(failures, mockWorker).retryDue();
        verify(failures).recoverExhausted(any());
        verify(mockWorker, never()).execute(eq(1L), any());
        verify(mockWorker).execute(eq(2L), any());
        String claim = String.join(" ", SearchBillingFailureMapper.class
                .getMethod("claim", Long.class, String.class, LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Update.class).value());
        assertTrue(claim.contains("retry_count < max_retries"));
        assertTrue(claim.contains("lease_until <= #{now}"));
        assertTrue(claim.contains("retry_count = retry_count + 1"));
        String recovery = String.join(" ", SearchBillingFailureMapper.class.getMethod("recoverExhausted", LocalDateTime.class)
                .getAnnotation(Update.class).value());
        assertTrue(recovery.contains("lease_until <= #{now}"));
        assertFalse(recovery.contains("retry_count = 0"));
        assertTrue(claim.contains("failure_type = 'SEARCH_BILLING'"));
        assertTrue(recovery.contains("failure_type = 'SEARCH_BILLING'"));
        String due = String.join(" ", SearchBillingFailureMapper.class.getMethod("findDue", LocalDateTime.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertTrue(due.contains("failure_type = 'SEARCH_BILLING'"));
    }

    @Test
    void modelEventsNeverEnterSearchBillingWorker() {
        SearchBillingFailure row = row(1);
        row.setFailureType("MODEL_SETTLE"); row.setMaxRetries(null);
        worker.execute(1L, "mine");
        worker.failed(1L, "mine", new TransientDataAccessResourceException("offline"));
        verifyNoInteractions(billing, usage);
        verify(failures, never()).update(any(), any(Wrapper.class));
    }
}
