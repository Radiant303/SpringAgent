package com.spring.gateway.messaging.service.impl;

import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.billing.service.ModelBillingService;
import com.spring.gateway.messaging.service.TurnDispatchService;
import com.spring.gateway.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitOperations.OperationsCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮次控制令牌派发测试。
 *
 * @author hanbing
 * @since 2026-10-13
 */
class TurnDispatchServiceUnitTests {

    @Test
    void publishesControlTokensForStartAndCancel() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        ModelBillingService billing = mock(ModelBillingService.class);
        when(ids.nextId()).thenReturn(42L);
        doAnswer(call -> {
            OperationsCallback<?> callback = call.getArgument(0);
            return callback.doInRabbit(rabbit);
        }).when(rabbit).invoke(any(OperationsCallback.class));
        TurnDispatchService service = new TurnDispatchServiceImpl(rabbit, ids);
        assertEquals(42L, service.dispatch(7L, "session-1", "hello", "token-a"));
        service.cancel(7L, "session-1", "token-b");
        var payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(rabbit).convertAndSend(eq(RabbitConfig.TURN_EXCHANGE), eq(RabbitConfig.QUEUE_DISPATCH), payload.capture());
        assertEquals("token-a", payload.getValue().get("controlToken"));
        verify(rabbit).convertAndSend(eq(RabbitConfig.TURN_EXCHANGE), eq(RabbitConfig.QUEUE_CANCEL), payload.capture());
        assertEquals("token-b", payload.getValue().get("controlToken"));
        org.mockito.Mockito.verifyNoInteractions(billing);
    }
}
