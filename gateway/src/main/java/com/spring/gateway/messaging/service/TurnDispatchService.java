package com.spring.gateway.messaging.service;

/**
 * 轮次消息派发业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface TurnDispatchService {
    long dispatch(long userId, String sessionId, String input, String controlToken);

    long dispatchBranch(long userId, String sessionId, String action, String branchId,
                        String input, String controlToken);

    void cancel(long userId, String sessionId, String controlToken);
}
