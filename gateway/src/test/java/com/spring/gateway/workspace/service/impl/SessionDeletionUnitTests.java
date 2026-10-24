package com.spring.gateway.workspace.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.workspace.entity.Work;
import com.spring.gateway.identity.mapper.*;
import com.spring.gateway.workspace.mapper.*;
import com.spring.gateway.workspace.service.SessionStreamControl;
import com.spring.gateway.workspace.service.InternalStoreService;
import com.spring.gateway.workspace.service.SessionService;
import com.spring.gateway.workspace.service.WorkService;
import com.spring.gateway.engine.client.EngineClient;
import com.spring.gateway.storage.service.FileStorageService;
import com.spring.gateway.storage.service.UserStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 会话删除业务测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
class SessionDeletionUnitTests {
    UserMapper users = mock(UserMapper.class);
    WorkMapper works = mock(WorkMapper.class);
    SessionMapper sessions = mock(SessionMapper.class);
    MessageMapper messages = mock(MessageMapper.class);
    EngineClient engine = mock(EngineClient.class);
    FileStorageService files = mock(FileStorageService.class);
    UserStorageService storage = mock(UserStorageService.class);
    SessionStreamControl streams = mock(SessionStreamControl.class);
    SessionService service = new SessionServiceImpl(sessions, mock(InternalStoreService.class), mock(WorkService.class),
            users, works, messages, files, storage, engine, streams);
    Session session;
    User user;
    Work work;

    @BeforeEach
    void prepare() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "session-deletion-tests"), Session.class);
        TransactionSynchronizationManager.initSynchronization();
        user = new User();
        user.setId(1L);
        work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(1L);
        session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(1L);
        session.setWorkId(work.getId());
        session.setStatus(Session.STATUS_ACTIVE);
        when(users.selectForUpdate(1L)).thenReturn(user);
        when(works.selectForUpdate(work.getId())).thenReturn(work);
        when(sessions.selectById(session.getId())).thenReturn(session);
        when(sessions.selectOne(any())).thenReturn(session);
    }

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void softDeletesAndReleasesRuntimeOnlyAfterCommit() {
        service.delete(1L, session.getId());
        verify(storage).deleteSessionDirectory(1L, work.getId(), session.getId());
        verify(files).deleteBySession(1L, work.getId(), session.getId());
        verify(messages).delete(any(Wrapper.class));
        verify(sessions).update(argThat((Wrapper<Session> wrapper) ->
                wrapper.getSqlSegment().contains("id") && wrapper.getSqlSet().contains("status")));
        verify(sessions, never()).deleteById(anyString());
        verify(streams, never()).closeSession(any());
        for (TransactionSynchronization hook : TransactionSynchronizationManager.getSynchronizations()) {
            hook.afterCommit();
            hook.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        verify(streams).closeSession(session.getId());
        verify(engine).finishSessionDeletion(eq(session.getId()), eq(1L), anyString(), eq(true));
    }

    @Test
    void keepsForeignAndAlreadyDeletedSessionsUntouched() {
        session.setUserId(2L);
        assertEquals(404, assertThrows(BizException.class, () -> service.delete(1L, session.getId())).getStatus());
        session.setUserId(1L);
        session.setStatus(Session.STATUS_DELETED);
        assertEquals(404, assertThrows(BizException.class, () -> service.delete(1L, session.getId())).getStatus());
        verifyNoInteractions(engine, storage, files, messages);
    }

    @Test
    void refusesBusySessionBeforeFileChanges() {
        doThrow(HttpClientErrorException.create(org.springframework.http.HttpStatus.CONFLICT, "busy", null, new byte[0], null))
                .when(engine).prepareSessionDeletion(anyString(), anyLong(), anyString());
        assertEquals(409, assertThrows(BizException.class, () -> service.delete(1L, session.getId())).getStatus());
        verifyNoInteractions(storage, files, messages);
    }

    @Test
    void refusesUnavailableEngineAndReleasesPreparationOnRollback() {
        doThrow(new IllegalStateException("unavailable"))
                .when(engine).prepareSessionDeletion(anyString(), anyLong(), anyString());
        assertEquals(503, assertThrows(BizException.class, () -> service.delete(1L, session.getId())).getStatus());
        for (TransactionSynchronization hook : TransactionSynchronizationManager.getSynchronizations()) {
            hook.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(engine).finishSessionDeletion(eq(session.getId()), eq(1L), anyString(), eq(false));
        verifyNoInteractions(storage, files, messages, streams);
    }
}
