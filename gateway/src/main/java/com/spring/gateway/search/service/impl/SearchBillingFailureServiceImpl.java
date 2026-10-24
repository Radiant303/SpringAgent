package com.spring.gateway.search.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SearchProviders;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.search.entity.SearchBillingFailure;
import com.spring.gateway.search.mapper.SearchBillingFailureMapper;
import com.spring.gateway.search.service.SearchBillingFailureService;
import com.spring.gateway.settings.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLTransientException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;

/**
 * 搜索计费失败持久化与人工标记。
 *
 * @author hanbing
 * @since 2026-10-18
 */
@Service
@RequiredArgsConstructor
public class SearchBillingFailureServiceImpl implements SearchBillingFailureService {
    private final SearchBillingFailureMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final JsonMapper json;
    private final SystemSettingService settings;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean recorded(Message message) {
        return mapper.countByHash(hash(message.getBody())) > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Message message, Exception error) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SearchBillingFailure row = new SearchBillingFailure();
        row.setId(ids.nextId());
        row.setDedupKey(hash(message.getBody()));
        row.setFailureType("SEARCH_BILLING");
        row.setRawBody(message.getBody().clone());
        try {
            row.setHeaders(json.writeValueAsString(message.getMessageProperties().getHeaders()));
        } catch (RuntimeException exception) {
            row.setHeaders("{}");
        }
        try {
            JsonNode node = json.readTree(message.getBody());
            row.setEventId(text(node, "eventId", 64));
            row.setProvider(text(node, "provider", 32));
            row.setSessionId(text(node, "sessionId", 36));
            row.setTurnId(text(node, "turnId", 64));
            row.setUserId(number(node, "userId"));
            row.setSearches(number(node, "searches"));
        } catch (RuntimeException ignored) {
            // 原文保留格式错误。
        }
        snapshot(row);
        row.setStatus(isTransient(error) ? "RETRYING" : "PENDING");
        row.setRetryCount(0);
        row.setMaxRetries(3);
        row.setNextRetryAt(isTransient(error) ? now.plusSeconds(1) : null);
        row.setErrorType(safe(error.getClass().getName(), 256));
        row.setErrorMessage(safe(error.getMessage() == null ? error.toString() : error.getMessage(), Integer.MAX_VALUE));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insertOnce(row);
    }

    private void snapshot(SearchBillingFailure row) {
        if (row.getProvider() == null || !SearchProviders.isSupported(row.getProvider())) {
            return;
        }
        try {
            BigDecimal unit = settings.getSearchPoints(row.getProvider());
            if (unit == null || unit.signum() < 0) {
                return;
            }
            unit = unit.setScale(6, RoundingMode.UNNECESSARY);
            if (unit.precision() > 20) {
                return;
            }
            row.setUnitPoints(unit);
            if (row.getSearches() != null && row.getSearches() >= 0) {
                BigDecimal cost = unit.multiply(BigDecimal.valueOf(row.getSearches()));
                if (cost.precision() <= 20) {
                    row.setCostPoints(cost);
                }
            }
        } catch (RuntimeException ignored) {
            // 单价未知时不填参考快照。
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void changeStatus(Long id, String status, String remark, Long operatorId) {
        if (status == null || !Set.of("PENDING", "RESOLVED", "IGNORED").contains(status)) {
            throw new BizException(400, "不允许的失败事件状态");
        }
        if (remark != null && remark.length() > 10000) {
            throw new BizException(400, "备注最多 10000 字符");
        }
        SearchBillingFailure row = mapper.lockById(id);
        if (row == null) {
            throw new BizException(404, "失败事件不存在");
        }
        if ("RETRYING".equals(row.getStatus()) || "SUCCEEDED".equals(row.getStatus())) {
            throw new BizException(409, "重试中或已计费事件不能人工修改");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SearchBillingFailure>()
                .eq(SearchBillingFailure::getId, id)
                .set(SearchBillingFailure::getStatus, status)
                .set(SearchBillingFailure::getHandledBy, operatorId)
                .set(SearchBillingFailure::getHandledAt, now)
                .set(SearchBillingFailure::getUpdatedAt, now)
                .set(SearchBillingFailure::getRemark, safe(remark, 10000)));
    }

    static boolean isTransient(Throwable error) {
        return SearchBillingFailureService.isTransient(error);
    }

    static String safe(String value, int max) {
        return SearchBillingFailureService.safe(value, max);
    }

    private static String text(JsonNode node, String key, int max) {
        JsonNode field = node == null ? null : node.get(key);
        return field != null && field.isTextual() ? safe(field.asString(), max) : null;
    }

    private static Long number(JsonNode node, String key) {
        JsonNode field = node == null ? null : node.get(key);
        return field != null && field.isIntegralNumber() && field.canConvertToLong() ? field.longValue() : null;
    }

    private static String hash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
