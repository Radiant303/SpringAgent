package com.spring.gateway.service;

import com.spring.gateway.config.SearchRabbitConfig;
import com.spring.gateway.dto.SearchUsageEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 搜索计费消息消费测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class SearchUsageListenerTests {
    @Test
    void rejectsFractionalSearchCountsDuringJsonConversion() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder()
                .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
        var factory = new SearchRabbitConfig().searchListenerContainerFactory(
                mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class), mapper);
        var converter = (JacksonJsonMessageConverter) org.springframework.test.util.ReflectionTestUtils
                .getField(factory, "messageConverter");
        assertNotNull(converter);
        var properties = new org.springframework.amqp.core.MessageProperties();
        properties.setContentType("application/json");
        properties.setInferredArgumentType(SearchUsageEvent.class);
        String json = "{\"eventId\":\"event\",\"source\":\"local\",\"provider\":\"exa\",\"searches\":1.9,"
                + "\"userId\":7,\"sessionId\":\"session\",\"turnId\":\"turn\"}";
        var message = new org.springframework.amqp.core.Message(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
        assertThrows(org.springframework.amqp.support.converter.MessageConversionException.class,
                () -> converter.fromMessage(message));
    }

    @Test
    void propagatesBillingFailuresForDeadLettering() {
        SearchBillingService billing = mock(SearchBillingService.class);
        SearchUsageEvent event = new SearchUsageEvent("event", "local", "exa", 1L, 7L,
                "session", "turn", null, null);
        RuntimeException failure = new IllegalStateException("数据库不可用");
        doThrow(failure).when(billing).chargeLocal(event);
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> new SearchUsageListener(billing).onSearchUsage(event)));
        verify(billing).chargeLocal(event);
    }

    @Test
    void declaresDedicatedDurableTopologyAndPreservesDeadLetters() {
        SearchRabbitConfig config = new SearchRabbitConfig();
        var exchange = config.searchExchange();
        var deadLetterExchange = config.searchDeadLetterExchange();
        var queue = config.searchUsageQueue();
        var deadLetterQueue = config.searchUsageDlq();
        assertEquals("springharness.search", exchange.getName());
        assertEquals("direct", exchange.getType());
        assertTrue(exchange.isDurable());
        assertEquals("springharness.search.dlx", deadLetterExchange.getName());
        assertTrue(deadLetterExchange.isDurable());
        assertEquals("springharness.search.usage", queue.getName());
        assertTrue(queue.isDurable());
        assertEquals(deadLetterExchange.getName(), queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals("search.usage.failed", queue.getArguments().get("x-dead-letter-routing-key"));
        assertEquals("springharness.search.usage.dlq", deadLetterQueue.getName());
        assertTrue(deadLetterQueue.isDurable());
        assertEquals("search.usage", config.searchUsageBinding(exchange, queue).getRoutingKey());
        assertEquals("search.usage.failed",
                config.searchUsageDlqBinding(deadLetterExchange, deadLetterQueue).getRoutingKey());
        var factory = config.searchListenerContainerFactory(
                mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class),
                tools.jackson.databind.json.JsonMapper.builder().build());
        assertEquals(Boolean.FALSE,
                org.springframework.test.util.ReflectionTestUtils.getField(factory, "defaultRequeueRejected"));
    }
}
