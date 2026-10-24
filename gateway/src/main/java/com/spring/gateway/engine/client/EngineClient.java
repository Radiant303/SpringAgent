package com.spring.gateway.engine.client;

import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.Set;

/**
 * 引擎内部 API 端口。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface EngineClient {
    int activeSessionsInWork(String workId);

    String sessionControlToken(String sessionId, long userId, String connectionId);

    Set<String> busySessions(Collection<String> sessionIds);

    void prepareSessionDeletion(String sessionId, long userId, String token);

    void finishSessionDeletion(String sessionId, long userId, String token, boolean committed);

    JsonNode testModel(String modelId);
}
