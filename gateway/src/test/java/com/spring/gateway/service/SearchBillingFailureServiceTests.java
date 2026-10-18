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
