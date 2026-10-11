package com.spring.gateway.service;

import com.spring.gateway.config.RabbitConfig;
import com.spring.gateway.entity.Session;
import com.spring.gateway.entity.UsageRecord;
import com.spring.gateway.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * turn 生命周期事件监听：token 消耗落账与积分结算。
 *
 * @author hanbing
 * @since 2026-10-02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnLifecycleListener {

    private final SessionMapper sessionMapper;
    private final BillingService billingService;

    @RabbitListener(queues = RabbitConfig.QUEUE_LIFECYCLE)
    public void onTurnLifecycle(Map<String, Object> payload) {
        if (!(payload.get("usage") instanceof Map<?, ?> usage)) {
            // 未真正运行的轮次：释放派发预扣（无预扣时静默跳过），随后原样返回
            billingService.releaseHold(asString(payload.get("turnId")));
            log.info("turn 生命周期事件（无用量，不入账）: {}", payload);
            return;
        }
        UsageRecord record = new UsageRecord();
        record.setTurnId(asString(payload.get("turnId")));
        record.setSessionId(asString(payload.get("sessionId")));
        record.setUserId(resolveUserId(payload));
        record.setModelName(asString(usage.get("modelName")));
        record.setRequests(asInt(usage.get("requests")));
        record.setInputTokens(asLong(usage.get("inputTokens")));
        record.setCacheReadTokens(asLong(usage.get("cacheReadTokens")));
        record.setCacheWriteTokens(asLong(usage.get("cacheWriteTokens")));
        record.setOutputTokens(asLong(usage.get("outputTokens")));
        record.setStatus(asString(payload.get("status")));
        record.setIsWake(Boolean.TRUE.equals(payload.get("wake")));
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        billingService.recordUsageAndSettle(record);
        log.info("turn 用量已入账并结算: turnId={} userId={} model={} in={}(cacheRead={}) out={}",
                record.getTurnId(), record.getUserId(), record.getModelName(),
                record.getInputTokens(), record.getCacheReadTokens(), record.getOutputTokens());
    }

    /** userId 优先取事件本身（派发时已放入），缺失时按会话反查兜底（如催醒轮归属丢失）。 */
    private Long resolveUserId(Map<String, Object> payload) {
        Long userId = asLong(payload.get("userId"));
        if (userId != null) {
            return userId;
        }
        Session session = sessionMapper.selectById(asString(payload.get("sessionId")));
        if (session == null) {
            throw new IllegalStateException("用量入账失败：会话不存在 " + payload.get("sessionId"));
        }
        return session.getUserId();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
