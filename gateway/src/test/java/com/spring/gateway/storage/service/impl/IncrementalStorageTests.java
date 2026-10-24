package com.spring.gateway.storage.service.impl;

import com.spring.gateway.identity.entity.User;
import com.spring.gateway.workspace.entity.Work;
import com.spring.gateway.storage.mapper.FileRecordMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import com.spring.gateway.storage.infrastructure.StorageException;
import com.spring.gateway.storage.service.UserStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 存储增量与恢复的回归测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class IncrementalStorageTests {
    @TempDir Path root;
    WorkMapper works;
    UserMapper users;
    UserStorageService service;
    User user;
    Work work;

    @BeforeEach
    void prepare() {
        works = mock(WorkMapper.class);
        users = mock(UserMapper.class);
        SessionMapper sessions = mock(SessionMapper.class);
        FileRecordMapper files = mock(FileRecordMapper.class);
        PlatformTransactionManager transaction = mock(PlatformTransactionManager.class);
        when(transaction.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = spy(new UserStorageServiceImpl(works, sessions, files, users, transaction));
        ReflectionTestUtils.setField(service, "dataRoot", root.toString());
        user = new User();
        user.setId(1L);
        user.setUsedBytes(10L);
        user.setKnowledgeBytes(3L);
        user.setStorageInitialized(true);
        work = new Work();
        work.setId("one");
        work.setUserId(1L);
        work.setSizeBytes(7L);
        work.setInternalBytes(0L);
        when(users.selectForUpdate(1L)).thenReturn(user);
        when(works.selectForUpdate("one")).thenReturn(work);
        when(users.updateStorage(anyLong(), anyLong(), anyLong(), anyBoolean())).thenAnswer(call -> {
            user.setUsedBytes(call.getArgument(1));
            user.setKnowledgeBytes(call.getArgument(2));
            user.setStorageInitialized(call.getArgument(3));
            return 1;
        });
        when(works.updateStorage(anyString(), anyLong(), anyLong())).thenAnswer(call -> {
            work.setSizeBytes(call.getArgument(1));
            work.setInternalBytes(call.getArgument(2));
            return 1;
        });
        when(works.selectList(any())).thenReturn(List.of(work));
        when(sessions.selectList(any())).thenReturn(List.of());
        when(files.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void growsAndShrinksBothUserAndWorkCounters() {
        service.addWorkBytes(1L, "one", 5L);
        verify(users).updateStorage(1L, 15L, 3L, true);
        verify(works).updateStorage("one", 12L, 0L);
        service.addWorkBytes(1L, "one", -4L);
        verify(users).updateStorage(1L, 11L, 3L, true);
        verify(works).updateStorage("one", 8L, 0L);
        assertEquals(11L, user.getUsedBytes());
        assertEquals(8L, work.getSizeBytes());
        verify(service, never()).directoryBytes(any());
    }

    @Test
    void rejectsUnderflowBeforeWritingCounters() {
        assertThrows(StorageException.class, () -> service.addWorkBytes(1L, "one", -8));
        verify(users, never()).updateStorage(any(), anyLong(), anyLong(), anyBoolean());
        verify(works, never()).updateStorage(any(), anyLong(), anyLong());
    }

    @Test
    void initializedSnapshotReadsStoredCounters() {
        doThrow(new AssertionError("unexpected scan")).when(service).reconcileLocked(any());
        UserStorageService.Snapshot snapshot = service.snapshot(user);
        assertEquals(10, snapshot.usedBytes());
        assertEquals(7, snapshot.workBytes("one"));
        verify(service, never()).directoryBytes(any());
        verify(service, never()).knowledgeBytes(anyLong());
    }

    @Test
    void recoveryCountsInternalTreeOnce() throws Exception {
        Files.createDirectories(root.resolve("works/one/.springagent/sessions"));
        Files.createDirectories(root.resolve("works/one/nested/.springagent"));
        Files.writeString(root.resolve("works/one/.springagent/sessions/messages.json"), "abc");
        Files.writeString(root.resolve("works/one/content.txt"), "de");
        Files.writeString(root.resolve("works/one/nested/.springagent/visible.txt"), "fg");
        assertEquals(7L, service.directoryBytes("one"));
        user.setStorageInitialized(false);
        service.ensureCurrent(user);
        verify(works).updateStorage("one", 7L, 3L);
        verify(users).updateStorage(1L, 7L, 0L, true);
    }

    @Test
    void successfulChangesInSameTransactionDoNotTriggerRecovery() {
        TransactionSynchronizationManager.initSynchronization();
        service.beginChange(1L);
        service.addWorkBytes(1L, "one", 5L);
        assertEquals(15L, service.snapshot(user).usedBytes());
        service.addWorkBytes(1L, "one", -4L);
        assertEquals(11L, service.snapshot(user).usedBytes());
        verify(service, never()).reconcileLocked(any());
    }

    @Test
    void deferredDeleteMarkerSurvivesCommitUntilRecovery() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        Path marker = service.beginDeferredChange(1L);
        service.ensureCurrent(user);
        verify(service, never()).reconcileLocked(any());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertTrue(Files.exists(marker));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        service.ensureCurrent(user);
        verify(service).reconcileLocked(user);
        assertTrue(Files.exists(marker));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertFalse(Files.exists(marker));
    }

    @Test
    void localDeletionReleasesActualBytesOnlyOnce() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        Path file = root.resolve("works/one/session/images/1.bin");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "1234567");
        Path knowledge = root.resolve("users/1/knowledge/declarative.md");
        Files.createDirectories(knowledge.getParent());
        Files.writeString(knowledge, "abc");
        var storage = new com.spring.gateway.storage.infrastructure.LocalStorageService(root.toString());
        service.removeLocalLocked(1L, "one", "works/one/session/images/1.bin", storage);
        assertFalse(Files.exists(file));
        assertEquals(3L, user.getUsedBytes());
        assertEquals(0L, work.getSizeBytes());
        service.removeLocalLocked(1L, "one", "works/one/session/images/1.bin", storage);
        assertEquals(3L, user.getUsedBytes());
        assertEquals(0L, work.getSizeBytes());
        verify(users, never()).updateStorage(eq(1L), longThat(bytes -> bytes < 0), anyLong(), anyBoolean());
    }

    @Test
    void counterFailureAfterLocalDeleteLeavesMarkerForRecovery() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        Path file = root.resolve("works/one/session/images/1.bin");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "1234567");
        Path knowledge = root.resolve("users/1/knowledge/declarative.md");
        Files.createDirectories(knowledge.getParent());
        Files.writeString(knowledge, "abc");
        doThrow(new StorageException("write failed")).doReturn(1).when(users)
                .updateStorage(anyLong(), anyLong(), anyLong(), anyBoolean());
        var storage = new com.spring.gateway.storage.infrastructure.LocalStorageService(root.toString());
        assertThrows(StorageException.class,
                () -> service.removeLocalLocked(1L, "one", "works/one/session/images/1.bin", storage));
        assertFalse(Files.exists(file));
        Path marker;
        try (var markers = Files.list(root.resolve("users/1/.storage-pending"))) {
            marker = markers.findFirst().orElseThrow();
        }
        assertTrue(Files.isRegularFile(marker));
        assertEquals(10L, user.getUsedBytes());
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        service.ensureCurrent(user);
        assertEquals(3L, user.getUsedBytes());
        assertEquals(0L, work.getSizeBytes());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertFalse(Files.exists(marker));
    }

    @Test
    void durableMarkerReconcilesFilesAndClearsAfterCommit() throws Exception {
        Files.createDirectories(root.resolve("works/one"));
        Files.writeString(root.resolve("works/one/content.txt"), "abc");
        Files.createDirectories(root.resolve("users/1/knowledge"));
        Files.writeString(root.resolve("users/1/knowledge/declarative.md"), "de");
        Path pending = root.resolve("users/1/.storage-pending/python-operation.pending");
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, "pending\n");
        TransactionSynchronizationManager.initSynchronization();
        service.ensureCurrent(user);
        verify(users).updateStorage(1L, 5L, 2L, true);
        verify(works).updateStorage("one", 3L, 0L);
        assertTrue(Files.exists(pending));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertFalse(Files.exists(pending));
        assertEquals(5, user.getUsedBytes());
    }
}
