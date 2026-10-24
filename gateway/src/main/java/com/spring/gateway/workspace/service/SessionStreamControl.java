package com.spring.gateway.workspace.service;

/**
 * 会话事件流控制端口。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface SessionStreamControl {
    void closeSession(String sessionId);
}
