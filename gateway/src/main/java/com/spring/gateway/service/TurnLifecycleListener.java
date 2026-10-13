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
import java.util.ArrayList;
import java.util.List;
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
        String turnId = asString(payload.get("turnId"));
        List<UsageRecord> records = readUsages(payload);
        if (records.isEmpty()) {
            billingService.releaseHold(turnId);
            log.info("turn 生命周期事件（无用量，不入账）: {}", payload);
            return;
        }
        billingService.recordUsageAndSettle(records);
        log.info("turn 用量已入账并结算: turnId={} models={}", turnId,
                records.stream().map(UsageRecord::getModelId).toList());
    }

    private List<UsageRecord> readUsages(Map<String, Object> payload) {
        Object raw = payload.get("usage");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> usages)) {
            throw new IllegalArgumentException("模型用量必须为分组列表");
        }
        List<Map<?, ?>> groups = new ArrayList<>();
        for (Object item : usages) {
            if (!(item instanceof Map<?, ?> group)) {
                throw new IllegalArgumentException("模型用量分组非法");
            }
            groups.add(group);
        }
        List<UsageRecord> records = new ArrayList<>();
        for (Map<?, ?> group : groups) {
            UsageRecord record = new UsageRecord();
            record.setTurnId(asString(payload.get("turnId")));
            record.setSessionId(asString(payload.get("sessionId")));
            record.setUserId(resolveUserId(payload));
            record.setModelId(asString(group.get("modelId")));
            record.setRequests(asInt(group.get("requests")));
            record.setInputTokens(asLong(group.get("inputTokens")));
            record.setCacheReadTokens(asLong(group.get("cacheReadTokens")));
            record.setCacheWriteTokens(asLong(group.get("cacheWriteTokens")));
            record.setOutputTokens(asLong(group.get("outputTokens")));
            record.setStatus(asString(payload.get("status")));
            record.setIsWake(Boolean.TRUE.equals(payload.get("wake")));
            record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            records.add(record);
        }
        return records;
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
