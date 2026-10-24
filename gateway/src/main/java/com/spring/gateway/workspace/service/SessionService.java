package com.spring.gateway.workspace.service;

import com.spring.gateway.workspace.dto.SessionSummary;

import java.util.List;

/**
 * 会话业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface SessionService {
    List<SessionSummary> list(Long userId, String workId);

    SessionSummary detail(Long userId, String sessionId);

    SessionSummary create(Long userId, String workId);

    void delete(Long userId, String sessionId);
}
