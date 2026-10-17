package com.spring.gateway.service.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

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
    @DisplayName("启动即创建可写工作区根目录")
    void initializesMissingWorksAtStartup() {
        Path root = tempDir.resolve("new/data");
        LocalStorageService storage = new LocalStorageService(root.toString());
        assertTrue(Files.isDirectory(root.resolve("works")));
        assertTrue(Files.isWritable(root.resolve("works")));
        assertTrue(storage.listObjects().isEmpty());
        assertFalse(Files.exists(root.resolve(".secrets")));
    }

    @Test
    @DisplayName("已有工作区文件及目录权限保持不变")
    void preservesExistingWorks() throws Exception {
        Path works = Files.createDirectories(tempDir.resolve("works"));
        Path existing = Files.writeString(works.resolve("fixture.txt"), "fixture-only");
        var permissions = Files.getFileStore(works).supportsFileAttributeView("posix")
                ? Files.getPosixFilePermissions(works) : null;
        storage();
        assertEquals("fixture-only", Files.readString(existing));
        if (permissions != null) {
            assertEquals(permissions, Files.getPosixFilePermissions(works));
        }
    }

    @Test
    @DisplayName("数据根或工作区为普通文件时启动失败")
    void rejectsNonDirectoryRootsAtStartup() throws Exception {
        Path rootFile = Files.writeString(tempDir.resolve("root-file"), "fixture-root");
        assertThrows(StorageException.class, () -> new LocalStorageService(rootFile.toString()));
        Path worksFile = Files.writeString(tempDir.resolve("works"), "fixture-works");
        assertThrows(StorageException.class, this::storage);
        assertEquals("fixture-root", Files.readString(rootFile));
        assertEquals("fixture-works", Files.readString(worksFile));
    }

    @Test
    @DisplayName("已有工作区不可写时明确启动失败")
    void rejectsUnwritableWorksAtStartup() throws Exception {
        Path works = Files.createDirectories(tempDir.resolve("works"));
        Path existing = Files.writeString(works.resolve("fixture.txt"), "fixture-only");
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.isWritable(works)).thenReturn(false);
            StorageException error = assertThrows(StorageException.class, this::storage);
            assertTrue(error.getMessage().contains("工作区初始化失败"));
            assertEquals("工作区目录不可写", error.getCause().getMessage());
        }
        assertEquals("fixture-only", Files.readString(existing));
    }

    @Test
    @DisplayName("创建工作区权限不足时启动失败")
    void rejectsDeniedDirectoryCreationAtStartup() {
        Path works = tempDir.resolve("works");
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("createDirectories")
                    && works.equals(invocation.getArgument(0))) {
                throw new AccessDeniedException(works.toString());
            }
            return invocation.callRealMethod();
        })) {
            StorageException error = assertThrows(StorageException.class, this::storage);
            assertTrue(error.getMessage().contains("工作区初始化失败"));
            assertTrue(error.getCause() instanceof AccessDeniedException);
        }
        assertFalse(Files.exists(works));
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
        Files.writeString(tempDir.resolve("works/work-1/agent-output.txt"), "fixture-only");
        List<ObjectStorageService.StoredObject> objects = storage.listObjects();
        assertEquals(1, objects.size());
        assertEquals(managed, objects.getFirst().key());
    }

    @Test
    @DisplayName("非受管、绝对、反斜杠及穿越 key 均拒绝")
    void rejectsUnmanagedAndAbsoluteKeys() {
        LocalStorageService storage = storage();
        for (String key : new String[]{".secrets/fixture.txt", "works/work-1/page/index.html",
                "/works/w/s/images/a.png", "C:/works/w/s/images/a.png",
                "works/w/s/images/../a.png", "works/w/s/images/a:stream", "works/w\\s/s/images/a.png"}) {
            assertThrows(StorageException.class, () -> storage.localPath(key));
            assertThrows(StorageException.class, () -> storage.delete(key));
            assertThrows(StorageException.class, () -> storage.upload(
                    new ByteArrayInputStream(new byte[]{1}), 1, key, "image/png"));
        }
    }

    @Test
    @DisplayName("缺失目录可新建，重复上传可覆盖，缺失文件可查询与删除")
    void missingParentsAndReplacementWork() throws Exception {
        LocalStorageService storage = new LocalStorageService(tempDir.resolve("new/data").toString());
        String key = StorageKeys.imageKey("w", "s", "fixture", "png");
        assertFalse(Files.exists(storage.localPath(key).orElseThrow()));
        storage.delete(key);
        storage.upload(new ByteArrayInputStream(new byte[]{1}), 1, key, "image/png");
        storage.upload(new ByteArrayInputStream(new byte[]{2, 3}), 2, key, "image/png");
        assertArrayEqualsContent(new byte[]{2, 3}, Files.readAllBytes(storage.localPath(key).orElseThrow()));
    }

    @Test
    @DisplayName("数据根与工作区根链接均拒绝")
    void rejectsLinkedRoots() throws Exception {
        Path outside = Files.createDirectories(tempDir.resolve(".secrets"));
        Path linkedRoot = tempDir.resolve("linked-root");
        createDirectoryLink(linkedRoot, outside);
        assertThrows(StorageException.class, () -> new LocalStorageService(linkedRoot.toString()));
        assertThrows(StorageException.class,
                () -> new LocalStorageService(linkedRoot.resolve("new/data").toString()));
        createDirectoryLink(tempDir.resolve("works"), outside);
        assertThrows(StorageException.class, this::storage);
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    @DisplayName("上传父目录链接不能写入或读取秘密目录")
    void rejectsLinkedUploadDirectory() throws Exception {
        Path secrets = Files.createDirectories(tempDir.resolve(".secrets"));
        Path session = Files.createDirectories(tempDir.resolve("works/w/s"));
        createDirectoryLink(session.resolve("images"), secrets);
        String key = StorageKeys.imageKey("w", "s", "fixture", "png");
        assertRejected(storage(), key);
        assertFalse(Files.exists(secrets.resolve("fixture.png")));
        assertTrue(storage().listObjects().isEmpty());
    }

    @Test
    @DisplayName("文件链接不能读取、覆盖或删除秘密文件")
    void rejectsLinkedFile() throws Exception {
        Path secret = Files.writeString(tempDir.resolve("fixture.txt"), "fixture-only");
        Path images = Files.createDirectories(tempDir.resolve("works/w/s/images"));
        Path link = images.resolve("fixture.png");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("文件符号链接不可用");
        }
        assertRejected(storage(), StorageKeys.imageKey("w", "s", "fixture", "png"));
        assertEquals("fixture-only", Files.readString(secret));
        assertTrue(Files.isSymbolicLink(link));
        assertTrue(storage().listObjects().isEmpty());
    }

    private static void assertRejected(LocalStorageService storage, String key) {
        assertThrows(StorageException.class, () -> storage.localPath(key));
        assertThrows(StorageException.class, () -> storage.delete(key));
        assertThrows(StorageException.class, () -> storage.upload(
                new ByteArrayInputStream(new byte[]{1}), 1, key, "image/png"));
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor(), "创建测试 junction 失败");
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private static void assertArrayEqualsContent(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i]);
        }
    }
}
