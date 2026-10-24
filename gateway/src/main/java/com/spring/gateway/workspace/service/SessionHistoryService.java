package com.spring.gateway.workspace.service;

import tools.jackson.databind.node.ObjectNode;

/**
 * 会话历史分页业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface SessionHistoryService {
    int SESSION_NOT_FOUND = -32002;

    ObjectNode queryHistory(String sessionId, Long userId, String cursor, Integer limit, String direction);
}
