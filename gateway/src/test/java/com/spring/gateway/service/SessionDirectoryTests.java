package com.spring.gateway.service;

import com.spring.gateway.entity.User;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.*;
import com.spring.gateway.service.storage.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 会话目录清理与字节扣减测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
class SessionDirectoryTests {
    @TempDir Path root;
    UserMapper users = mock(UserMapper.class);
    WorkMapper works = mock(WorkMapper.class);
    UserStorageService storage;
    User user;
    Work work;
    String sid;

    @BeforeEach
    void prepare() {
        TransactionSynchronizationManager.initSynchronization();
        storage = new UserStorageService(works, mock(SessionMapper.class), mock(FileRecordMapper.class),
                users, mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(storage, "dataRoot", root.toString());
        user = new User();
        user.setId(1L);
        user.setUsedBytes(100L);
        user.setKnowledgeBytes(20L);
        user.setStorageInitialized(true);
        work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(1L);
        work.setSizeBytes(80L);
        work.setInternalBytes(10L);
        sid = UUID.randomUUID().toString();
        when(users.selectForUpdate(1L)).thenReturn(user);
        when(works.selectForUpdate(work.getId())).thenReturn(work);
    }

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void removesWholeSessionTreeAndDeductsActualBytesOnlyOnce() throws Exception {
        Path workRoot = root.resolve("works").resolve(work.getId());
        Path session = workRoot.resolve(sid);
        Files.createDirectories(session.resolve("images"));
        Files.write(session.resolve("images/a.png"), new byte[7]);
        Files.write(session.resolve("extra.md"), new byte[13]);
        Files.write(workRoot.resolve("shared.md"), new byte[5]);
        Path other = workRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectories(other);
        Files.write(other.resolve("keep.md"), new byte[5]);
        storage.deleteSessionDirectory(1L, work.getId(), sid);
        assertFalse(Files.exists(session));
        assertTrue(Files.exists(workRoot.resolve("shared.md")));
        assertTrue(Files.exists(other.resolve("keep.md")));
        verify(users).updateStorage(1L, 80L, 20L, true);
        verify(works).updateStorage(work.getId(), 60L, 10L);
        storage.deleteSessionDirectory(1L, work.getId(), sid);
        verify(users, times(1)).updateStorage(anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void missingDirectoryReleasesZeroBytes() {
        storage.deleteSessionDirectory(1L, work.getId(), sid);
        verify(users, never()).updateStorage(anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void refusesForeignWorkAndMalformedIdentifiers() {
        work.setUserId(2L);
        assertThrows(StorageException.class, () -> storage.deleteSessionDirectory(1L, work.getId(), sid));
        work.setUserId(1L);
        assertThrows(IllegalArgumentException.class, () -> storage.deleteSessionDirectory(1L, work.getId(), "../other"));
        verify(users, never()).updateStorage(anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void refusesAFileAtSessionDirectoryPath() throws Exception {
        Path workRoot = root.resolve("works").resolve(work.getId());
        Files.createDirectories(workRoot);
        Files.writeString(workRoot.resolve(sid), "keep");
        assertThrows(StorageException.class, () -> storage.deleteSessionDirectory(1L, work.getId(), sid));
        assertEquals("keep", Files.readString(workRoot.resolve(sid)));
    }
}
