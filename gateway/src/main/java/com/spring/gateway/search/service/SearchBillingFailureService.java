package com.spring.gateway.search.service;

import com.spring.gateway.common.BizException;
import org.springframework.amqp.core.Message;
import org.springframework.dao.TransientDataAccessException;

import java.sql.SQLTransientException;
import java.util.Set;

/**
 * 搜索计费失败业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface SearchBillingFailureService {
    boolean recorded(Message message);

    void record(Message message, Exception error);

    void changeStatus(Long id, String status, String remark, Long operatorId);

    static boolean isTransient(Throwable error) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof BizException) {
                return false;
            }
            if (current instanceof TransientDataAccessException || current instanceof SQLTransientException
                    || current instanceof org.springframework.transaction.CannotCreateTransactionException
                    || current instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
                    || current instanceof java.sql.SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    static String safe(String value, int max) {
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), max));
        value.codePoints().limit(max).forEach(point -> result.appendCodePoint(
                point >= 0xD800 && point <= 0xDFFF ? 0xFFFD : point));
        return result.toString();
    }
}
