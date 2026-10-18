package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.entity.SearchBillingFailure;
import com.spring.gateway.mapper.SearchBillingFailureMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 搜索失败事件持久化与状态测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
class SearchBillingFailureServiceTests {
    private final SearchBillingFailureMapper mapper = mock(SearchBillingFailureMapper.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final SearchBillingFailureService service = new SearchBillingFailureService(mapper, ids, JsonMapper.builder().build(), settings);

    @BeforeEach
    void init() {
        when(ids.nextId()).thenReturn(123L);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SearchBillingFailure.class);
    }

    private Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private SearchBillingFailure captured() {
        ArgumentCaptor<SearchBillingFailure> captor = ArgumentCaptor.forClass(SearchBillingFailure.class);
        verify(mapper).insertOnce(captor.capture());
        return captor.getValue();
    }

    @Test
    void malformedJsonKeepsOriginalBytesAndPermanentPendingState() {
        Message message = new Message(new byte[]{'{', (byte) 0xff}, new MessageProperties());
        service.record(message, new IllegalArgumentException("invalid JSON"));
        SearchBillingFailure row = captured();
        assertArrayEquals(message.getBody(), row.getRawBody());
        assertEquals(64, row.getDedupKey().length());
        assertEquals("PENDING", row.getStatus());
        assertEquals(0, row.getRetryCount());
        assertEquals(3, row.getMaxRetries());
        assertNull(row.getNextRetryAt());
        assertNull(row.getCostPoints());
        assertNull(row.getUserId());
    }

    @Test
    void hostileMetadataCannotOverflowQueryFieldsOrDecimalSnapshot() {
        service.record(message("{\"eventId\":\"" + "x".repeat(1000) + "\",\"provider\":\"exa\","
                + "\"sessionId\":\"" + "s".repeat(1000) + "\",\"userId\":9223372036854775808,"
                + "\"searches\":9223372036854775807}"), new BizException(400, "invalid"));
        SearchBillingFailure row = captured();
        assertEquals(64, row.getEventId().length());
        assertEquals(36, row.getSessionId().length());
        assertNull(row.getUserId());
        assertNull(row.getCostPoints());
    }

    @Test
    void transientFailureGetsReferencePriceAndFirstDelay() {
        when(settings.getSearchPoints("exa")).thenReturn(new BigDecimal("0.123456"));
        service.record(message("{\"provider\":\"exa\",\"searches\":1}"), new TransientDataAccessResourceException("offline"));
        SearchBillingFailure row = captured();
        assertEquals("RETRYING", row.getStatus());
        assertEquals(row.getCreatedAt().plusSeconds(1), row.getNextRetryAt());
        assertEquals(new BigDecimal("0.123456"), row.getCostPoints());
    }

    @Test
    void identicalPayloadHashDeduplicatesButConflictingEventBodyDoesNot() {
        service.record(message("{\"eventId\":\"same\",\"searches\":1}"), new BizException(400, "bad"));
        service.record(message("{\"eventId\":\"same\",\"searches\":1}"), new BizException(400, "bad"));
        service.record(message("{\"eventId\":\"same\",\"searches\":2}"), new BizException(409, "conflict"));
        ArgumentCaptor<SearchBillingFailure> captor = ArgumentCaptor.forClass(SearchBillingFailure.class);
        verify(mapper, times(3)).insertOnce(captor.capture());
        List<SearchBillingFailure> rows = captor.getAllValues();
        assertEquals(rows.get(0).getDedupKey(), rows.get(1).getDedupKey());
        assertNotEquals(rows.get(0).getDedupKey(), rows.get(2).getDedupKey());
    }

    @Test
    void manualStateOnlyMarksAuditWithoutBillingOrResettingAttempts() {
        SearchBillingFailure row = new SearchBillingFailure();
        row.setId(7L); row.setStatus("PENDING"); row.setRetryCount(3); row.setMaxRetries(3);
        when(mapper.lockById(7L)).thenReturn(row);
        service.changeStatus(7L, "RESOLVED", "核实完成", 99L);
        assertEquals(3, row.getRetryCount());
        verify(mapper).update(isNull(), argThat(update -> {
            var changes = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SearchBillingFailure>) update;
            String sql = changes.getSqlSet();
            assertTrue(sql.contains("handled_by"));
            assertTrue(sql.contains("remark"));
            assertFalse(sql.contains("retry_count"));
            assertFalse(sql.contains("cost_points"));
            assertTrue(changes.getParamNameValuePairs().containsValue("RESOLVED"));
            assertTrue(changes.getParamNameValuePairs().containsValue(99L));
            assertTrue(changes.getParamNameValuePairs().containsValue("核实完成"));
            return true;
        }));
        verifyNoInteractions(settings);
        assertThrows(BizException.class, () -> service.changeStatus(7L, "SUCCEEDED", null, 99L));
        row.setStatus("RETRYING");
        assertThrows(BizException.class, () -> service.changeStatus(7L, "IGNORED", null, 99L));
        row.setFailureType("MODEL_SETTLE"); row.setStatus("SUCCEEDED");
        assertThrows(BizException.class, () -> service.changeStatus(7L, "PENDING", null, 99L));
    }

    private final com.spring.gateway.mapper.PointsHoldMapper holds = mock(com.spring.gateway.mapper.PointsHoldMapper.class);
    private final com.spring.gateway.mapper.UsageRecordMapper usages = mock(com.spring.gateway.mapper.UsageRecordMapper.class);
    private final com.spring.gateway.mapper.UserMapper users = mock(com.spring.gateway.mapper.UserMapper.class);
    private final com.spring.gateway.mapper.SearchUsageRecordMapper nativeUsages = mock(com.spring.gateway.mapper.SearchUsageRecordMapper.class);

    private ModelBillingFailureService modelService() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.spring.gateway.entity.PointsHold.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.spring.gateway.entity.UsageRecord.class);
        when(mapper.lockByKey(anyString())).thenAnswer(invocation -> {
            throw new AssertionError("audit row missing");
        });
        doAnswer(invocation -> {
            SearchBillingFailure seed = invocation.getArgument(0);
            doReturn(seed).when(mapper).lockByKey(seed.getDedupKey());
            return 1;
        }).when(mapper).insertOnce(any());
        return new ModelBillingFailureService(mapper, holds, usages, users, nativeUsages, ids, JsonMapper.builder().build());
    }

    private com.spring.gateway.dto.ModelBillingFailureRequest report(String delivery, boolean known, int count) {
        String requestId = "0123456789abcdef0123456789abcdef";
        var settlement = new com.spring.gateway.dto.SettleModelRequest(requestId, 7L, "finished", known,
                0L, 0L, 0L, 0L, null);
        return new com.spring.gateway.dto.ModelBillingFailureRequest(requestId, 7L, settlement, delivery, count,
                "2026-10-19T00:00:00Z", "2026-10-19T00:01:00Z", "ConnectError", "offline");
    }

    private com.spring.gateway.entity.PointsHold actual(String status, boolean known) {
        var hold = new com.spring.gateway.entity.PointsHold();
        hold.setRequestId(report("DELIVERED", known, 1).requestId());
        hold.setUserId(7L); hold.setStatus(status); hold.setModelId("model");
        hold.setSessionId("session"); hold.setTurnId("turn"); hold.setAgentName("main");
        hold.setSettledAt(java.time.LocalDateTime.of(2026, 10, 19, 0, 2));
        var usage = new com.spring.gateway.entity.UsageRecord();
        usage.setRequestId(hold.getRequestId()); usage.setUserId(7L); usage.setUsageKnown(known);
        usage.setModelId(hold.getModelId()); usage.setStatus("finished");
        usage.setInputTokens(0L); usage.setCacheReadTokens(0L); usage.setCacheWriteTokens(0L); usage.setOutputTokens(0L);
        usage.setCostPoints(new BigDecimal("0.123456"));
        when(holds.selectOne(any())).thenReturn(hold);
        when(usages.selectOne(any())).thenReturn(usage);
        return hold;
    }

    @Test
    void firstDeliveredReportCreatesSuccessArchiveUsingActualCharge() {
        var model = modelService();
        actual("SETTLED", true);
        assertEquals("SUCCEEDED", model.report(report("DELIVERED", true, 2)));
        verify(mapper).updateById(argThat((SearchBillingFailure row) -> {
            assertEquals("MODEL_SETTLE", row.getFailureType());
            assertEquals("MODEL_SETTLE:" + row.getRequestId(), row.getDedupKey());
            assertEquals("SETTLED", row.getSettlementStatus());
            assertEquals("DELIVERED", row.getDeliveryStatus());
            assertEquals(2, row.getRetryCount());
            assertNull(row.getMaxRetries());
            assertEquals("{}", row.getHeaders());
            assertTrue(new String(row.getRawBody(), StandardCharsets.UTF_8).contains("webSearches"));
            assertEquals(new BigDecimal("0.123456"), row.getCostPoints());
            assertNotNull(row.getCompletedAt());
            assertEquals("offline", row.getErrorMessage());
            return true;
        }));
        verify(holds, never()).updateById(any(com.spring.gateway.entity.PointsHold.class));
        verify(usages, never()).insert(any(com.spring.gateway.entity.UsageRecord.class));
        verify(usages, never()).updateById(any(com.spring.gateway.entity.UsageRecord.class));
        verifyNoInteractions(settings);
    }

    @Test
    void deliveredUnknownWithoutFailuresRemainsPending() {
        var model = modelService();
        actual("UNKNOWN", false);
        assertEquals("PENDING", model.report(report("DELIVERED", false, 0)));
        verify(mapper).updateById(argThat((SearchBillingFailure row) -> row.getRetryCount() == 0
                && "UNKNOWN".equals(row.getSettlementStatus()) && row.getCompletedAt() == null));
    }

    @Test
    void deliveryAloneCannotInventSuccessfulSettlement() {
        var model = modelService();
        actual("HELD", true);
        assertEquals("PENDING", model.report(report("DELIVERED", true, 1)));
        verify(mapper).updateById(argThat((SearchBillingFailure row) -> row.getCostPoints() == null && row.getCompletedAt() == null));
    }

    @Test
    void retryingReportReconcilesAlreadySettledRequest() {
        var model = modelService();
        actual("SETTLED", true);
        assertEquals("SUCCEEDED", model.report(report("RETRYING", true, 1)));
    }

    @Test
    void discardedOnlyArchivesMissingKnownZeroUsageAsIgnored() {
        var model = modelService();
        assertEquals("IGNORED", model.report(report("DISCARDED", true, 1)));
        verify(mapper).updateById(argThat((SearchBillingFailure row) -> "DISCARDED".equals(row.getSettlementStatus())
                && row.getCostPoints() == null && row.getCompletedAt() != null));
        actual("SETTLED", true);
        assertThrows(BizException.class, () -> model.report(report("DISCARDED", true, 1)));
    }

    @Test
    void reportsValidateNestedOwnershipActualOwnershipAndUtcTimes() {
        var model = modelService();
        var request = report("RETRYING", true, 1);
        var mismatch = new com.spring.gateway.dto.ModelBillingFailureRequest(request.requestId(), 8L, request.settlement(),
                request.deliveryStatus(), 1, request.firstFailedAt(), request.lastFailedAt(), "error", "bad");
        assertThrows(BizException.class, () -> model.report(mismatch));
        actual("HELD", true).setUserId(8L);
        assertThrows(BizException.class, () -> model.report(request));
        var invalidTime = new com.spring.gateway.dto.ModelBillingFailureRequest(request.requestId(), 7L, request.settlement(),
                "RETRYING", 1, "2026-10-19T08:00:00+08:00", request.lastFailedAt(), "error", "bad");
        assertThrows(BizException.class, () -> model.report(invalidTime));
        verify(mapper, never()).insertOnce(any());
    }

    @Test
    void retrySuccessUpdatesOriginalArchiveAndRetainsFailureDetails() {
        var model = modelService();
        actual("HELD", false);
        assertEquals("RETRYING", model.report(report("RETRYING", true, 2)));
        var archive = captured();
        Long originalId = archive.getId();
        doReturn(0).when(mapper).insertOnce(any());
        actual("SETTLED", true);
        assertEquals("SUCCEEDED", model.report(report("DELIVERED", true, 2)));
        assertEquals(originalId, archive.getId());
        assertEquals(2, archive.getRetryCount());
        assertEquals("ConnectError", archive.getErrorType());
        assertEquals("offline", archive.getErrorMessage());
        assertNotNull(archive.getFirstFailedAt());
        assertNotNull(archive.getCompletedAt());
        assertNull(archive.getMaxRetries());
    }

    @Test
    void lateFailureCannotRegressSuccessfulArchiveOrReplaceSettlement() {
        var model = modelService();
        actual("SETTLED", true);
        model.report(report("DELIVERED", true, 3));
        var archive = captured();
        byte[] original = archive.getRawBody().clone();
        doReturn(0).when(mapper).insertOnce(any());
        model.report(report("RETRYING", false, 1));
        assertEquals("SUCCEEDED", archive.getStatus());
        assertEquals("DELIVERED", archive.getDeliveryStatus());
        assertEquals(3, archive.getRetryCount());
        assertArrayEquals(original, archive.getRawBody());
        assertEquals("SETTLED", archive.getSettlementStatus());
    }

    @Test
    void duplicateConcurrentReportsUseOneStableKeyAndLockedMerge() throws Exception {
        var model = modelService();
        actual("SETTLED", true);
        var stored = new java.util.concurrent.atomic.AtomicReference<SearchBillingFailure>();
        var arrived = new java.util.concurrent.CyclicBarrier(2);
        doAnswer(invocation -> {
            stored.compareAndSet(null, invocation.getArgument(0));
            arrived.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return 1;
        }).when(mapper).insertOnce(any());
        doAnswer(invocation -> stored.get()).when(mapper).lockByKey(anyString());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> model.report(report("DELIVERED", true, 2)));
            var second = executor.submit(() -> model.report(report("RETRYING", true, 1)));
            assertEquals("SUCCEEDED", first.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("SUCCEEDED", second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            verify(mapper, times(2)).lockByKey("MODEL_SETTLE:" + report("DELIVERED", true, 2).requestId());
            String sql = String.join(" ", SearchBillingFailureMapper.class.getMethod("lockByKey", String.class)
                    .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
            assertTrue(sql.contains("FOR UPDATE"));
            assertEquals("SUCCEEDED", stored.get().getStatus());
        } finally {
            executor.shutdownNow();
        }
    }

    private com.spring.gateway.dto.ModelBillingFailureRequest report(
            String delivery, com.spring.gateway.dto.SettleModelRequest settlement) {
        var base = report(delivery, true, 2);
        return new com.spring.gateway.dto.ModelBillingFailureRequest(base.requestId(), base.userId(), settlement,
                delivery, base.failureCount(), base.firstFailedAt(), base.lastFailedAt(), "Conflict409", "usage conflict");
    }

    private com.spring.gateway.entity.SearchUsageRecord nativeActual(com.spring.gateway.entity.PointsHold hold, long searches) {
        var record = new com.spring.gateway.entity.SearchUsageRecord();
        record.setEventId("native:" + hold.getRequestId()); record.setSource("native"); record.setProvider("native");
        record.setRequestId(hold.getRequestId()); record.setUserId(hold.getUserId());
        record.setSessionId(hold.getSessionId()); record.setTurnId(hold.getTurnId()); record.setAgentName(hold.getAgentName());
        record.setSearches(searches); record.setUnitPoints(new BigDecimal("0.200000"));
        record.setCostPoints(record.getUnitPoints().multiply(BigDecimal.valueOf(searches)));
        when(nativeUsages.selectForUpdate(record.getEventId())).thenReturn(record);
        return record;
    }

    @Test
    void settledHoldCannotHideAnyTokenUsageConflict() {
        var model = modelService();
        String requestId = report("DELIVERED", true, 1).requestId();
        var conflicting = List.of(
                new com.spring.gateway.dto.SettleModelRequest(requestId, 7L, "error", true, 0L, 0L, 0L, 0L, null),
                new com.spring.gateway.dto.SettleModelRequest(requestId, 7L, "finished", false, 0L, 0L, 0L, 0L, null),
                new com.spring.gateway.dto.SettleModelRequest(requestId, 7L, "finished", true, 1L, 0L, 0L, 0L, null),
                new com.spring.gateway.dto.SettleModelRequest(requestId, 7L, "finished", true, 1L, 1L, 0L, 0L, null),
                new com.spring.gateway.dto.SettleModelRequest(requestId, 7L, "finished", true, 1L, 0L, 1L, 0L, null),
                new com.spring.gateway.dto.SettleModelRequest(requestId, 7L, "finished", true, 0L, 0L, 0L, 1L, null));
        for (String delivery : List.of("DELIVERED", "RETRYING")) {
            for (int index = 0; index < conflicting.size(); index++) {
                actual("SETTLED", true);
                if (index == 3 || index == 4) {
                    usages.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>()).setInputTokens(1L);
                }
                assertEquals("RETRYING".equals(delivery) ? "RETRYING" : "PENDING",
                        model.report(report(delivery, conflicting.get(index))), "conflict field " + index);
            }
        }
        verify(mapper, times(12)).updateById(argThat((SearchBillingFailure row) -> row.getCompletedAt() == null
                && row.getCostPoints() == null && "Conflict409".equals(row.getErrorType())
                && !"SUCCEEDED".equals(row.getStatus())));
        verifyNoInteractions(nativeUsages);
    }

    @Test
    void nativeSearchMustHaveMatchingActualRecordBeforeSuccess() {
        var model = modelService();
        var hold = actual("SETTLED", true);
        var base = report("DELIVERED", true, 2).settlement();
        var settlement = new com.spring.gateway.dto.SettleModelRequest(base.requestId(), base.userId(), base.status(),
                base.usageKnown(), base.inputTokens(), base.cacheReadTokens(), base.cacheWriteTokens(), base.outputTokens(), 2L);
        assertEquals("PENDING", model.report(report("DELIVERED", settlement)));
        nativeActual(hold, 2L);
        assertEquals("SUCCEEDED", model.report(report("DELIVERED", settlement)));
        verify(nativeUsages, times(2)).selectForUpdate("native:" + base.requestId());
    }

    @Test
    void nativeRecordReplayConflictsNeverBecomeSuccessful() {
        var model = modelService();
        var hold = actual("SETTLED", true);
        var base = report("RETRYING", true, 2).settlement();
        var settlement = new com.spring.gateway.dto.SettleModelRequest(base.requestId(), base.userId(), base.status(),
                base.usageKnown(), base.inputTokens(), base.cacheReadTokens(), base.cacheWriteTokens(), base.outputTokens(), 2L);
        List<java.util.function.Consumer<com.spring.gateway.entity.SearchUsageRecord>> conflicts = List.of(
                record -> record.setEventId("other"), record -> record.setSource("local"),
                record -> record.setProvider("exa"), record -> record.setRequestId("other"),
                record -> record.setUserId(8L), record -> record.setSessionId("other"),
                record -> record.setTurnId("other"), record -> record.setAgentName("child"),
                record -> record.setSearches(1L), record -> record.setCostPoints(BigDecimal.ONE),
                record -> record.setUnitPoints(null), record -> record.setCostPoints(null));
        for (var conflict : conflicts) {
            conflict.accept(nativeActual(hold, 2L));
            assertEquals("RETRYING", model.report(report("RETRYING", settlement)));
        }
        verify(mapper, times(conflicts.size())).updateById(argThat((SearchBillingFailure row) -> row.getCompletedAt() == null
                && row.getCostPoints() == null && "Conflict409".equals(row.getErrorType())));
    }

    @Test
    void explicitZeroSearchesStillRequiresNativeReplayRecord() {
        var model = modelService();
        var hold = actual("SETTLED", true);
        var base = report("DELIVERED", true, 2).settlement();
        var settlement = new com.spring.gateway.dto.SettleModelRequest(base.requestId(), base.userId(), base.status(),
                base.usageKnown(), base.inputTokens(), base.cacheReadTokens(), base.cacheWriteTokens(), base.outputTokens(), 0L);
        assertEquals("PENDING", model.report(report("DELIVERED", settlement)));
        nativeActual(hold, 0L);
        assertEquals("SUCCEEDED", model.report(report("DELIVERED", settlement)));
    }

    @Test
    void modelMismatchCannotInventSuccessfulSettlement() {
        var model = modelService();
        actual("SETTLED", true);
        usages.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>()).setModelId("other");
        assertEquals("PENDING", model.report(report("DELIVERED", true, 1)));
    }

    @Test
    void newerConflictCannotRewriteAnySuccessfulArchiveFields() {
        var model = modelService();
        actual("SETTLED", true);
        model.report(report("DELIVERED", true, 3));
        var archive = captured();
        byte[] body = archive.getRawBody().clone();
        var originalTime = archive.getLastFailedAt();
        var originalUpdated = archive.getUpdatedAt();
        doReturn(0).when(mapper).insertOnce(any());
        var base = report("RETRYING", false, 10);
        var late = new com.spring.gateway.dto.ModelBillingFailureRequest(base.requestId(), base.userId(), base.settlement(),
                base.deliveryStatus(), 10, base.firstFailedAt(), "2026-10-19T02:00:00Z", "Conflict409", "different usage");
        assertEquals("SUCCEEDED", model.report(late));
        assertArrayEquals(body, archive.getRawBody());
        assertEquals("offline", archive.getErrorMessage());
        assertEquals("ConnectError", archive.getErrorType());
        assertEquals(3, archive.getRetryCount());
        assertEquals(originalTime, archive.getLastFailedAt());
        assertEquals(originalUpdated, archive.getUpdatedAt());
        verify(mapper, times(1)).updateById(any(SearchBillingFailure.class));
    }

    @Test
    void reportLocksUserBeforeHoldUsageNativeRecordAndAuditWithoutCharging() {
        var model = modelService();
        var hold = actual("SETTLED", true);
        nativeActual(hold, 2L);
        var base = report("DELIVERED", true, 2).settlement();
        var settlement = new com.spring.gateway.dto.SettleModelRequest(base.requestId(), base.userId(), base.status(),
                base.usageKnown(), base.inputTokens(), base.cacheReadTokens(), base.cacheWriteTokens(), base.outputTokens(), 2L);
        assertEquals("SUCCEEDED", model.report(report("DELIVERED", settlement)));
        var order = inOrder(users, holds, usages, nativeUsages, mapper);
        order.verify(users).selectBalanceForUpdate(7L);
        order.verify(holds).selectOne(argThat(query -> query.getSqlSegment().endsWith("FOR UPDATE")));
        order.verify(usages).selectOne(argThat(query -> query.getSqlSegment().endsWith("FOR UPDATE")));
        order.verify(nativeUsages).selectForUpdate("native:" + base.requestId());
        order.verify(mapper).insertOnce(any());
        order.verify(mapper).lockByKey("MODEL_SETTLE:" + base.requestId());
        order.verify(mapper).updateById(any(SearchBillingFailure.class));
        verify(users, never()).updateBalance(anyLong(), any());
        verify(holds, never()).updateById(any(com.spring.gateway.entity.PointsHold.class));
        verify(usages, never()).updateById(any(com.spring.gateway.entity.UsageRecord.class));
        verify(nativeUsages, never()).insert(any(com.spring.gateway.entity.SearchUsageRecord.class));
    }

    @Test
    void commitFailureEscapesListenerEvenAfterSuccessfulInsert() {
        var transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = mock(org.springframework.transaction.TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        var commitFailure = new org.springframework.transaction.TransactionSystemException("commit failed");
        doThrow(commitFailure).when(transactions).commit(status);
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                transactions, new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        SearchBillingFailureService proxied = (SearchBillingFailureService) factory.getProxy();
        assertSame(commitFailure, assertThrows(RuntimeException.class,
                () -> proxied.record(message("{broken"), new IllegalArgumentException("bad JSON"))));
        verify(mapper).insertOnce(any());
        verify(transactions).commit(status);
    }

    @Test
    void failurePersistenceRequiresIndependentCommit() throws Exception {
        Transactional annotation = SearchBillingFailureService.class.getMethod("record", Message.class, Exception.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation());
        doThrow(new IllegalStateException("insert failed")).when(mapper).insertOnce(any());
        assertThrows(IllegalStateException.class, () -> service.record(message("bad"), new IllegalArgumentException()));
    }
}
