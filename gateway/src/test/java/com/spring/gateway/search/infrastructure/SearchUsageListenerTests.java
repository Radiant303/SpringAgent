package com.spring.gateway.search.infrastructure;

import com.spring.gateway.config.SearchRabbitConfig;
import com.spring.gateway.search.dto.SearchUsageEvent;
import com.spring.gateway.search.service.SearchBillingFailureService;
import com.spring.gateway.search.service.SearchBillingService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 搜索计费消息消费测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
class SearchUsageListenerTests {
    private final SearchBillingService billing = mock(SearchBillingService.class);
    private final SearchBillingFailureService failures = mock(SearchBillingFailureService.class);
    private final JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    private final SearchUsageListener listener = new SearchUsageListener(billing, failures, json);

    private Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    @Test
    void persistsMalformedJsonBeforeReturning() {
        Message message = message("{broken");
        listener.onSearchUsage(message);
        verify(failures).record(eq(message), any(Exception.class));
        verifyNoInteractions(billing);
    }

    @Test
    void rawAdapterIgnoresHostileContentTypeAndEncoding() throws Exception {
        Message message = message("{broken");
        message.getMessageProperties().setContentType("text/plain");
        message.getMessageProperties().setContentEncoding("not-a-charset");
        var method = SearchUsageListener.class.getMethod("onSearchUsage", Message.class);
        var adapter = new org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter(listener, method);
        adapter.setMessageConverter(new com.spring.gateway.config.SearchRawMessageConverter());
        var methods = new org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory();
        methods.afterPropertiesSet();
        adapter.setHandlerAdapter(new org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter(
                methods.createInvocableHandlerMethod(listener, method)));
        adapter.onMessage(message, null);
        verify(failures).record(eq(message), any(Exception.class));
    }

    @Test
    void durableDuplicateDoesNotRebillOrResetManualOutcome() {
        Message message = message("duplicate");
        when(failures.recorded(message)).thenReturn(true);
        listener.onSearchUsage(message);
        verifyNoInteractions(billing);
        verify(failures, never()).record(any(), any());
    }

    @Test
    void malformedDeadLetterIsPersistedBeforeCompletion() {
        Message message = message("{broken");
        new SearchUsageDlqListener(listener).onDeadLetter(message);
        verify(failures).record(eq(message), any(Exception.class));
    }

    @Test
    void persistsFractionalSearchCountsInsteadOfConverterRejection() {
        Message message = message("{\"searches\":1.9}");
        listener.onSearchUsage(message);
        verify(failures).record(eq(message), any(Exception.class));
        verifyNoInteractions(billing);
    }

    @Test
    void propagatesPersistenceFailureForDeadLettering() {
        Message message = message("{broken");
        RuntimeException failure = new IllegalStateException("数据库不可用");
        doThrow(failure).when(failures).record(eq(message), any());
        assertSame(failure, assertThrows(RuntimeException.class, () -> listener.onSearchUsage(message)));
    }

    @Test
    void commitsBillingOrFailureBeforeReturn() {
        Message message = message("{\"eventId\":\"event\",\"source\":\"local\",\"provider\":\"exa\",\"searches\":1,"
                + "\"userId\":7,\"sessionId\":\"session\",\"turnId\":\"turn\"}");
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("offline"))
                .when(billing).chargeLocal(any(SearchUsageEvent.class));
        listener.onSearchUsage(message);
        var order = inOrder(billing, failures);
        order.verify(billing).chargeLocal(any());
        order.verify(failures).record(eq(message), any());
    }

    @Test
    void deadLetterPersistenceFailureAlwaysRequeues() {
        Message message = message("{broken");
        doThrow(new IllegalStateException("offline")).when(failures).record(eq(message), any());
        SearchUsageDlqListener dlq = spy(new SearchUsageDlqListener(listener));
        doNothing().when(dlq).pauseBeforeRequeue();
        assertThrows(ImmediateRequeueAmqpException.class, () -> dlq.onDeadLetter(message));
        verify(dlq).pauseBeforeRequeue();
    }

    @Test
    void declaresDurableDeadLetterTopologyAndSafeFactories() {
        SearchRabbitConfig config = new SearchRabbitConfig();
        var exchange = config.searchExchange();
        var dead = config.searchDeadLetterExchange();
        var queue = config.searchUsageQueue();
        assertTrue(exchange.isDurable());
        assertTrue(dead.isDurable());
        assertTrue(queue.isDurable());
        assertTrue(config.searchUsageDlq().isDurable());
        assertEquals(dead.getName(), queue.getArguments().get("x-dead-letter-exchange"));
        var connection = mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        var factory = config.searchListenerContainerFactory(connection, json);
        assertEquals(Boolean.FALSE, org.springframework.test.util.ReflectionTestUtils.getField(factory, "defaultRequeueRejected"));
        var dlq = config.searchDlqListenerContainerFactory(connection);
        assertEquals(Boolean.TRUE, org.springframework.test.util.ReflectionTestUtils.getField(dlq, "defaultRequeueRejected"));
    }
}
