package com.spring.gateway.workspace.service.impl;

import com.spring.gateway.engine.client.EngineClient;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.storage.service.FileStorageService;
import com.spring.gateway.storage.service.UserStorageService;
import com.spring.gateway.workspace.dto.SessionSummary;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.workspace.mapper.MessageMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import com.spring.gateway.workspace.service.InternalStoreService;
import com.spring.gateway.workspace.service.SessionStreamControl;
import com.spring.gateway.workspace.service.WorkService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话列表运行状态单元测试。
 *
 * @author hanbing
 * @since 2026-11-11
 */
class SessionServiceUnitTests {

    private final SessionMapper sessionMapper = mock(SessionMapper.class);
    private final EngineClient engineClient = mock(EngineClient.class);
    private final SessionServiceImpl sessionService = new SessionServiceImpl(
            sessionMapper, mock(InternalStoreService.class), mock(WorkService.class),
            mock(UserMapper.class), mock(WorkMapper.class), mock(MessageMapper.class),
            mock(FileStorageService.class), mock(UserStorageService.class), engineClient,
            mock(SessionStreamControl.class));

    @Test
    void listMergesBusySessions() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(session("s1"), session("s2")));
        when(engineClient.busySessions(List.of("s1", "s2"))).thenReturn(Set.of("s1"));

        List<SessionSummary> result = sessionService.list(42L, "w1");

        assertTrue(result.get(0).busy());
        assertFalse(result.get(1).busy());
    }

    @Test
    void listDegradesToNotBusyWhenEngineFails() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(session("s1"), session("s2")));
        when(engineClient.busySessions(List.of("s1", "s2")))
                .thenThrow(new IllegalStateException("引擎不可达"));

        List<SessionSummary> result = sessionService.list(42L, "w1");

        assertFalse(result.get(0).busy());
        assertFalse(result.get(1).busy());
    }

    private Session session(String sessionId) {
        Session row = new Session();
        row.setId(sessionId);
        row.setWorkId("w1");
        row.setTitle("标题");
        row.setCreatedAt(LocalDateTime.of(2026, 11, 11, 8, 0));
        row.setUpdatedAt(row.getCreatedAt());
        return row;
    }
}
