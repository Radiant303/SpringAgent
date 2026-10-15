package com.spring.gateway.service.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地磁盘存储的单元测试。
 *
 * @author hanbing
 * @since 2026-10-14
 */
class LocalStorageServiceTests {

    @TempDir
    Path tempDir;

    private LocalStorageService storage() {
        return new LocalStorageService(tempDir.toString());
    }

    @Test
    @DisplayName("上传落盘并可按 key 取回路径，删除后文件消失")
    void uploadAndDelete() throws Exception {
        LocalStorageService storage = storage();
        String key = StorageKeys.imageKey("work-1", "session-1", "a".repeat(32), "png");
        byte[] content = {1, 2, 3, 4};
        String stored = storage.upload(new ByteArrayInputStream(content), content.length, key, "image/png");
        assertEquals(key, stored);
        Path path = storage.localPath(key).orElseThrow();
        assertTrue(path.startsWith(tempDir));
        assertArrayEqualsContent(content, Files.readAllBytes(path));
        storage.delete(key);
        assertFalse(Files.exists(path));
    }

    @Test
    @DisplayName("key 含 .. 穿越时拒绝")
    void rejectsTraversal() {
        LocalStorageService storage = storage();
        assertThrows(StorageException.class, () -> storage.upload(
                new ByteArrayInputStream(new byte[]{1}), 1, "works/../../evil.png", "image/png"));
    }

    @Test
    @DisplayName("对账列举只覆盖受管 key，不碰工作区其他文件")
    void listOnlyManaged() throws Exception {
        LocalStorageService storage = storage();
        String managed = StorageKeys.imageKey("work-1", "session-1", "b".repeat(32), "jpg");
        storage.upload(new ByteArrayInputStream(new byte[]{1}), 1, managed, "image/jpeg");
        // 工作区里的代理产物：同根目录但不属于受管 key
        storage.upload(new ByteArrayInputStream(new byte[]{1}), 1, "works/work-1/agent-output.txt", "text/plain");
        List<ObjectStorageService.StoredObject> objects = storage.listObjects();
        assertEquals(1, objects.size());
        assertEquals(managed, objects.getFirst().key());
    }

    private static void assertArrayEqualsContent(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i]);
        }
    }
}
