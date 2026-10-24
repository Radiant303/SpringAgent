package com.spring.gateway.messaging.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.config.RabbitConfig;
import com.spring.gateway.messaging.service.TurnDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * turn 派发：把拦下的 turn/start、turn/cancel 转为 MQ 消息。
 *
 * @author hanbing
 * @since 2026-10-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TurnDispatchServiceImpl implements TurnDispatchService {

    /** 同步确认超时：超过视为发布失败 */
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    /** 单轮输入长度上限（字符），超过直接拒绝不消耗积分 */
    private static final int MAX_INPUT_CHARS = 64_000;

    private final RabbitTemplate rabbitTemplate;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 派发一轮对话。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param input 用户输入
     * @param controlToken 会话控制令牌
     * @return 轮次 ID
     * @throws BizException 输入超长（400）
     */
    public long dispatch(long userId, String sessionId, String input, String controlToken) {
        if (input != null && input.length() > MAX_INPUT_CHARS) {
            throw new BizException(400, "输入内容过长（最多 " + MAX_INPUT_CHARS + " 字符）");
        }
        long turnId = idGenerator.nextId();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("turnId", turnId);
        message.put("sessionId", sessionId);
        message.put("userId", userId);
        message.put("controlToken", controlToken);
        message.put("input", input);
        message.put("enqueuedAt", Instant.now().toString());
        publish(RabbitConfig.QUEUE_DISPATCH, message);
        return turnId;
    }

    /**
     * 取消会话当前轮次。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param controlToken 会话控制令牌
     */
    public void cancel(long userId, String sessionId, String controlToken) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("sessionId", sessionId);
        message.put("userId", userId);
        message.put("controlToken", controlToken);
        message.put("enqueuedAt", Instant.now().toString());
        publish(RabbitConfig.QUEUE_CANCEL, message);
    }

    /** 发送并同步等待 broker 确认；确认失败/超时抛 AmqpException，由调用方决定回退 */
    private void publish(String routingKey, Map<String, Object> message) {
        rabbitTemplate.invoke(template -> {
            template.convertAndSend(RabbitConfig.TURN_EXCHANGE, routingKey, message);
            template.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }
}
