package com.spring.gateway.messaging.infrastructure;

import com.spring.gateway.config.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 死信队列监听。
 *
 * @author hanbing
 * @since 2026-10-02
 */
@Slf4j
@Component
public class DlqListener {

    @RabbitListener(
            queues = {RabbitConfig.QUEUE_DISPATCH_DLQ, RabbitConfig.QUEUE_CANCEL_DLQ,
                    RabbitConfig.QUEUE_LIFECYCLE_DLQ},
            containerFactory = "dlqListenerContainerFactory")
    public void onDeadLetter(byte[] body, @Headers Map<String, Object> headers) {
        log.error("DLQ 告警: firstDeathQueue={} firstDeathReason={} deathCount={} body={}",
                headers.get("x-first-death-queue"),
                headers.get("x-first-death-reason"),
                headers.get("x-retry-count"),
                new String(body, StandardCharsets.UTF_8));
    }
}
