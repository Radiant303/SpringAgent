package com.spring.gateway.service;

import com.spring.gateway.config.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 轮次生命周期事件监听。
 *
 * @author hanbing
 * @since 2026-10-02
 */
@Slf4j
@Component
public class TurnLifecycleListener {
    @RabbitListener(queues = RabbitConfig.QUEUE_LIFECYCLE)
    public void onTurnLifecycle(Map<String, Object> payload) {
        log.info("轮次结束: turnId={} sessionId={} status={}",
                payload.get("turnId"), payload.get("sessionId"), payload.get("status"));
    }
}
