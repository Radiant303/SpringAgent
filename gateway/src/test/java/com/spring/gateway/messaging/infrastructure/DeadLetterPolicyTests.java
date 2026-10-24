package com.spring.gateway.messaging.infrastructure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.spring.gateway.config.RabbitConfig;
import com.spring.gateway.config.SearchRabbitConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 死信消费策略测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
class DeadLetterPolicyTests {
    @Test
    void conversationAcknowledgesAfterListenerReturns() {
        var factory = new RabbitConfig().dlqListenerContainerFactory(mock(ConnectionFactory.class));
        assertEquals(AcknowledgeMode.AUTO, ReflectionTestUtils.getField(factory, "acknowledgeMode"));
    }

    @Test
    void conversationLogsEachDeadLetterImmediatelyAtErrorLevel() {
        Logger logger = (Logger) LoggerFactory.getLogger(DlqListener.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            DlqListener listener = new DlqListener();
            listener.onDeadLetter("failed-turn".getBytes(StandardCharsets.UTF_8),
                    Map.of("x-first-death-queue", RabbitConfig.QUEUE_DISPATCH,
                            "x-first-death-reason", "rejected", "x-retry-count", 3));
            listener.onDeadLetter("broken-json".getBytes(StandardCharsets.UTF_8), Map.of());
            assertEquals(2, logs.list.size());
            assertTrue(logs.list.stream().allMatch(event -> event.getLevel() == Level.ERROR));
            assertTrue(logs.list.getFirst().getFormattedMessage().contains("failed-turn"));
            assertTrue(logs.list.getFirst().getFormattedMessage().contains(RabbitConfig.QUEUE_DISPATCH));
            assertTrue(logs.list.getLast().getFormattedMessage().contains("broken-json"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void monitorsSearchQueuesAlongsideConversationQueues() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        Map<String, Long> depths = new MqMonitor(admin).queueDepths();
        assertEquals(8, depths.size());
        assertTrue(depths.containsKey(SearchRabbitConfig.SEARCH_QUEUE));
        assertTrue(depths.containsKey(SearchRabbitConfig.SEARCH_DLQ));
        verify(admin).getQueueInfo(SearchRabbitConfig.SEARCH_QUEUE);
        verify(admin).getQueueInfo(SearchRabbitConfig.SEARCH_DLQ);
        assertTrue(depths.values().stream().allMatch(java.util.Objects::isNull));
    }
}
