package com.spring.gateway.storage.service.impl;

import com.spring.gateway.storage.mapper.FileRecordMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.storage.infrastructure.StorageException;
import com.spring.gateway.storage.service.UserStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 用户知识文件的存储计量测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class UserStorageServiceTests {
    @TempDir
    Path root;

    @Test
    void missingFilesUseZeroWithoutCreatingDirectories() {
        assertEquals(0, service().knowledgeBytes(1));
        assertFalse(Files.exists(root.resolve("users")));
    }

    @Test
    void threeFilesAreCountedPerUserAndChangesReleaseSpace() throws Exception {
        UserStorageService service = service();
        Path first = root.resolve("users/1/knowledge");
        Path second = root.resolve("users/2/knowledge");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("declarative.md"), "知识", StandardCharsets.UTF_8);
        Files.writeString(first.resolve("procedural.md"), "abc", StandardCharsets.UTF_8);
        Files.writeString(first.resolve("conditional.md"), "de", StandardCharsets.UTF_8);
        Files.writeString(first.resolve(".knowledge-temp.tmp"), "ignored", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("declarative.md"), "other", StandardCharsets.UTF_8);
        assertEquals(11, service.knowledgeBytes(1));
        assertEquals(5, service.knowledgeBytes(2));
        Files.writeString(first.resolve("procedural.md"), "", StandardCharsets.UTF_8);
        assertEquals(8, service.knowledgeBytes(1));
        Files.delete(first.resolve("declarative.md"));
        assertEquals(2, service.knowledgeBytes(1));
        assertEquals(5, service.knowledgeBytes(2));
    }

    @Test
    void directoryAtFilePathIsRejected() throws Exception {
        Files.createDirectories(root.resolve("users/1/knowledge/declarative.md"));
        assertThrows(StorageException.class, () -> service().knowledgeBytes(1));
    }

    private UserStorageService service() {
        UserStorageService service = new UserStorageServiceImpl(
                mock(WorkMapper.class), mock(SessionMapper.class), mock(FileRecordMapper.class),
                mock(UserMapper.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "dataRoot", root.toString());
        return service;
    }
}
