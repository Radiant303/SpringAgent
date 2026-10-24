package com.spring.gateway.workspace.service;

import com.spring.gateway.workspace.dto.InternalAppendResult;
import com.spring.gateway.workspace.dto.InternalMessagePage;
import com.spring.gateway.workspace.entity.Session;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 引擎内部会话存储业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface InternalStoreService {
    Session createSession(String sessionId, Long userId, String workId);

    Session getSession(String sessionId);

    List<Session> listUserSessions(Long userId, String workId);

    InternalMessagePage getMessages(String sessionId, Integer segmentNo);

    InternalAppendResult appendMessages(String sessionId, boolean newSegment, String title, List<JsonNode> messages);

    String workspacePath(String workId);
}
