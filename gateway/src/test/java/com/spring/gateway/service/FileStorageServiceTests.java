package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.entity.FileRecord;
import com.spring.gateway.entity.Session;
import com.spring.gateway.entity.User;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.FileRecordMapper;
import com.spring.gateway.mapper.SessionMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.mapper.WorkMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件存储及配额的集成测试。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.data-root=${java.io.tmpdir}/springharness-test-data")
class FileStorageServiceTests {

    @Autowired private FileStorageService fileStorageService;
    @Autowired private StorageReconcileTask reconcileTask;
    @Autowired private WorkService workService;
    @Autowired private UserStorageService userStorageService;
    @Autowired private FileRecordMapper fileRecordMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkMapper workMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private SnowflakeIdGenerator idGenerator;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean(name = "aliyun_oss") private com.spring.gateway.service.storage.AliyunOssStorageService remoteStorage;
    @MockitoBean private SystemSettingService systemSettingService;
    @MockitoBean private ReconcileScheduler reconcileScheduler;
    @MockitoBean private EngineClient engineClient;
    @org.springframework.beans.factory.annotation.Value("${app.data-root}")
    private String dataRoot;

    private final List<Long> userIds = new ArrayList<>();
    private final List<String> workIds = new ArrayList<>();

    @BeforeEach
    void configureStorage() {
        when(systemSettingService.getStorageType()).thenReturn("local");
        when(systemSettingService.getWorkMaxBytes()).thenReturn(1024L);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!userIds.isEmpty()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                fileRecordMapper.delete(new LambdaQueryWrapper<FileRecord>().in(FileRecord::getUserId, userIds));
                sessionMapper.delete(new LambdaQueryWrapper<Session>().in(Session::getUserId, userIds));
                workMapper.delete(new LambdaQueryWrapper<Work>().in(Work::getUserId, userIds));
                userMapper.deleteByIds(userIds);
            });
        }
        List<Path> roots = new ArrayList<>();
        workIds.forEach(id -> roots.add(Path.of(dataRoot, "works", id)));
        userIds.forEach(id -> roots.add(Path.of(dataRoot, "users", id.toString())));
        for (Path root : roots) {
            if (Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    @Test
    void uploadThenDeleteCommitsBeforeRemovingFile() throws Exception {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        FileRecord row = upload(userId, session, 10);
        Path disk = diskPath(row);
        assertEquals(10, userMapper.selectById(userId).getUsedBytes());
        assertTrue(Files.isRegularFile(disk));
        assertEquals(10, Files.size(disk));

        fileStorageService.delete(userId, row.getId());
        assertEquals(0, fileCount(session));
        assertEquals(10, userMapper.selectById(userId).getUsedBytes());
        assertTrue(Files.isRegularFile(disk));
        TestTransaction.flagForCommit();
        TestTransaction.end();
        assertTrue(Files.notExists(disk));
        assertEquals(0, userMapper.selectById(userId).getUsedBytes());
        assertEquals(0, workMapper.selectById(session.getWorkId()).getSizeBytes());
    }

    @Test
    void lockingReadsBypassMyBatisSessionCache() {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        userMapper.selectForUpdate(userId);
        workMapper.selectForUpdate(session.getWorkId());
        jdbc.update("UPDATE users SET used_bytes = 15, knowledge_bytes = 3, storage_initialized = TRUE WHERE id = ?", userId);
        jdbc.update("UPDATE works SET size_bytes = 12 WHERE id = ?", session.getWorkId());
        assertEquals(15L, userMapper.selectForUpdate(userId).getUsedBytes());
        assertEquals(12L, workMapper.selectForUpdate(session.getWorkId()).getSizeBytes());
        jdbc.update("UPDATE users SET used_bytes = 11 WHERE id = ?", userId);
        jdbc.update("UPDATE works SET size_bytes = 8 WHERE id = ?", session.getWorkId());
        assertEquals(11L, userMapper.selectForUpdate(userId).getUsedBytes());
        assertEquals(8L, workMapper.selectForUpdate(session.getWorkId()).getSizeBytes());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void remoteDeletesAndProjectReleaseDoNotDoubleSubtract() throws Exception {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        Path generated = Path.of(dataRoot, "works", session.getWorkId(), "generated.txt");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "1234");
        Path knowledge = Path.of(workService.knowledgePath(userId), "declarative.md");
        Files.createDirectories(knowledge.getParent());
        Files.writeString(knowledge, "ab");
        when(systemSettingService.getStorageType()).thenReturn("aliyun_oss");
        when(remoteStorage.upload(any(), anyLong(), anyString(), anyString())).thenAnswer(call -> call.getArgument(2));
        when(remoteStorage.localPath(anyString())).thenReturn(java.util.Optional.empty());
        FileRecord first = upload(userId, session, 3);
        FileRecord second = upload(userId, session, 5);
        assertEquals(14L, userMapper.selectById(userId).getUsedBytes());
        assertEquals(12L, workMapper.selectById(session.getWorkId()).getSizeBytes());
        fileStorageService.delete(userId, first.getId());
        assertEquals(11L, userMapper.selectById(userId).getUsedBytes());
        assertEquals(9L, workMapper.selectById(session.getWorkId()).getSizeBytes());
        assertEquals(404, assertThrows(BizException.class, () -> fileStorageService.delete(userId, first.getId())).getStatus());
        fileStorageService.deleteByWork(session.getWorkId());
        assertEquals(6L, userMapper.selectById(userId).getUsedBytes());
        assertEquals(4L, workMapper.selectById(session.getWorkId()).getSizeBytes());
        assertEquals(0, fileCount(session));
        verify(remoteStorage, times(1)).delete(first.getPath());
        verify(remoteStorage, times(1)).delete(second.getPath());
        assertEquals(0, reconcileTask.reconcileUser(userId));
        assertEquals(6L, userMapper.selectById(userId).getUsedBytes());
        workService.hardDelete(userId, session.getWorkId());
        assertEquals(2L, userMapper.selectById(userId).getUsedBytes());
        assertNull(workMapper.selectById(session.getWorkId()));
        assertTrue(Files.notExists(generated));
    }

    @Test
    void consecutiveIncrementalWritesUseCurrentLockedCounters() throws Exception {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        Path generated = Path.of(dataRoot, "works", session.getWorkId(), "generated.txt");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "1234567");
        Path knowledge = Path.of(workService.knowledgePath(userId), "declarative.md");
        Files.createDirectories(knowledge.getParent());
        Files.writeString(knowledge, "abc");
        assertEquals(10L, workService.storageOverview(userId).usedBytes());
        userMapper.selectForUpdate(userId);
        userStorageService.beginChange(userId);
        Files.writeString(generated, "123456789012");
        userStorageService.addWorkBytes(userId, session.getWorkId(), 5);
        assertEquals(15L, userMapper.selectForUpdate(userId).getUsedBytes());
        assertEquals(12L, workMapper.selectForUpdate(session.getWorkId()).getSizeBytes());
        userStorageService.beginChange(userId);
        Files.writeString(generated, "12345678");
        userStorageService.addWorkBytes(userId, session.getWorkId(), -4);
        assertEquals(11L, userMapper.selectForUpdate(userId).getUsedBytes());
        assertEquals(8L, workMapper.selectForUpdate(session.getWorkId()).getSizeBytes());
    }

    @Test
    void localUploadUsesActualBytesInsteadOfDeclaredSize() {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        FileRecord row = fileStorageService.upload(userId, session.getId(), "a.bin", "application/octet-stream",
                2, new ByteArrayInputStream(new byte[7]));
        assertEquals(7L, row.getSizeBytes());
        assertEquals(7L, userMapper.selectForUpdate(userId).getUsedBytes());
        assertEquals(7L, workMapper.selectForUpdate(session.getWorkId()).getSizeBytes());
    }

    @Test
    void actualUploadSizeCannotBypassQuota() {
        Long userId = insertUser(4);
        Session session = insertSession(userId);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        assertEquals(409, assertThrows(BizException.class, () -> fileStorageService.upload(userId, session.getId(),
                "a.bin", "application/octet-stream", 2, new ByteArrayInputStream(new byte[7]))).getStatus());
        assertEquals(0, fileCount(session));
        assertEquals(0L, userMapper.selectById(userId).getUsedBytes());
        assertEquals(0L, workMapper.selectById(session.getWorkId()).getSizeBytes());
    }

    @Test
    void localDeleteReleasesRecoveredActualSize() throws Exception {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        FileRecord row = upload(userId, session, 6);
        Path marker = Path.of(dataRoot, "users", userId.toString(), ".storage-pending", UUID.randomUUID() + ".pending");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "pending");
        Files.write(diskPath(row), new byte[9]);
        fileStorageService.delete(userId, row.getId());
        assertEquals(9L, userMapper.selectForUpdate(userId).getUsedBytes());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        assertFalse(Files.exists(diskPath(row)));
        assertFalse(Files.exists(marker));
        assertEquals(0L, userMapper.selectById(userId).getUsedBytes());
        assertEquals(0L, workMapper.selectById(session.getWorkId()).getSizeBytes());
    }

    @Test
    void uploadRollbackRemovesFileAndCounter() {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        FileRecord row = new TransactionTemplate(transactionManager).execute(status -> {
            FileRecord uploaded = upload(userId, session, 6);
            assertTrue(Files.isRegularFile(diskPath(uploaded)));
            status.setRollbackOnly();
            return uploaded;
        });
        assertNotNull(row);
        assertTrue(Files.notExists(diskPath(row)));
        assertEquals(0, fileCount(session));
        assertEquals(0, userMapper.selectById(userId).getUsedBytes());
    }

    @Test
    void deleteRollbackPreservesFileAndCounter() {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        FileRecord row = upload(userId, session, 6);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            fileStorageService.delete(userId, row.getId());
            assertTrue(Files.isRegularFile(diskPath(row)));
            status.setRollbackOnly();
        });
        assertEquals(1, fileCount(session));
        assertEquals(6, userMapper.selectById(userId).getUsedBytes());
        assertTrue(Files.isRegularFile(diskPath(row)));
    }

    @Test
    void uploadOverQuotaDoesNotReadContent() {
        Long userId = insertUser(4);
        Session session = insertSession(userId);
        ByteArrayInputStream content = new ByteArrayInputStream(new byte[8]);
        BizException e = assertThrows(BizException.class, () -> fileStorageService.upload(userId,
                session.getId(), "a.png", "image/png", 8, content));
        assertEquals(409, e.getStatus());
        assertEquals(8, content.available());
        assertEquals(0, fileCount(session));
        assertEquals(0, userMapper.selectById(userId).getUsedBytes());
    }

    @Test
    void uploadOverSizeLimitDoesNotReadContent() {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        ByteArrayInputStream content = new ByteArrayInputStream(new byte[8]);
        BizException e = assertThrows(BizException.class, () -> fileStorageService.upload(userId,
                session.getId(), "a.png", "image/png", FileStorageService.MAX_FILE_BYTES + 1, content));
        assertEquals(400, e.getStatus());
        assertEquals(8, content.available());
        assertEquals(0, fileCount(session));
    }

    @Test
    void userQuotaIncludesOtherWorkspaceFiles() throws Exception {
        Long userId = insertUser(10);
        Session target = insertSession(userId);
        Session other = insertSession(userId);
        Path generated = Path.of(dataRoot, "works", other.getWorkId(), "generated.txt");
        Files.createDirectories(generated.getParent());
        Files.write(generated, new byte[8]);
        BizException e = assertThrows(BizException.class, () -> upload(userId, target, 4));
        assertEquals(409, e.getStatus());
        assertEquals(0, fileCount(target));
    }

    @Test
    void knowledgeUsesUserQuotaAndReleasesSpaceAfterChange() throws Exception {
        Long userId = insertUser(10);
        Session session = insertSession(userId);
        Path knowledge = Path.of(workService.knowledgePath(userId));
        Files.createDirectories(knowledge);
        Files.writeString(knowledge.resolve("declarative.md"), "12345678");
        assertEquals(8, workService.storageOverview(userId).usedBytes());
        assertEquals(409, assertThrows(BizException.class, () -> upload(userId, session, 4)).getStatus());
        Path markers = Path.of(dataRoot, "users", userId.toString(), ".storage-pending");
        Files.createDirectories(markers);
        Files.writeString(markers.resolve(UUID.randomUUID() + ".pending"), "pending");
        Files.writeString(knowledge.resolve("declarative.md"), "12");
        upload(userId, session, 4);
        assertEquals(6, workService.storageOverview(userId).usedBytes());
        assertEquals(0, reconcileTask.reconcileUser(userId));
        assertEquals(6, workService.storageOverview(userId).usedBytes());
        Files.writeString(markers.resolve(UUID.randomUUID() + ".pending"), "pending");
        Files.delete(knowledge.resolve("declarative.md"));
        assertEquals(4, workService.storageOverview(userId).usedBytes());
    }

    @Test
    void knowledgeIsSharedOnceAndSurvivesProjectDeletion() throws Exception {
        Long userId = insertUser(1024);
        Session first = insertSession(userId);
        insertSession(userId);
        Path knowledge = Path.of(workService.knowledgePath(userId));
        Files.createDirectories(knowledge);
        Files.writeString(knowledge.resolve("procedural.md"), "shared");
        assertEquals(6, workService.storageOverview(userId).usedBytes());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        workService.hardDelete(userId, first.getWorkId());
        assertEquals("shared", Files.readString(knowledge.resolve("procedural.md")));
        assertEquals(6, workService.storageOverview(userId).usedBytes());
        assertEquals(1, workMapper.selectCount(new LambdaQueryWrapper<Work>().eq(Work::getUserId, userId)));
    }

    @Test
    void workQuotaIncludesGeneratedFilesAndInitializesDirectoryCounter() throws Exception {
        Long userId = insertUser(1024);
        User update = new User();
        update.setId(userId);
        update.setWorkQuotaBytes(10L);
        userMapper.updateById(update);
        Session session = insertSession(userId);
        Path generated = Path.of(dataRoot, "works", session.getWorkId(), "generated.txt");
        Files.createDirectories(generated.getParent());
        Files.write(generated, new byte[8]);
        BizException e = assertThrows(BizException.class, () -> upload(userId, session, 4));
        assertEquals(409, e.getStatus());
        assertEquals(0, fileCount(session));
        assertEquals(8, workMapper.selectById(session.getWorkId()).getSizeBytes());
        assertEquals(8, userMapper.selectById(userId).getUsedBytes());
    }

    @Test
    void storageTypeIsSelectedOnce() {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        when(systemSettingService.getStorageType()).thenReturn("local", "aliyun_oss");
        FileRecord row = upload(userId, session, 4);
        assertEquals("local", row.getStorageType());
        verify(systemSettingService, times(1)).getStorageType();
    }

    @Test
    void deleteForeignFile() {
        Long ownerId = insertUser(1024);
        Long otherId = insertUser(1024);
        Session session = insertSession(ownerId);
        FileRecord row = upload(ownerId, session, 4);
        BizException e = assertThrows(BizException.class,
                () -> fileStorageService.delete(otherId, row.getId()));
        assertEquals(404, e.getStatus());
        assertEquals(1, fileCount(session));
    }

    @Test
    void deleteByWorkCommitsBeforeRemovingFile() {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        FileRecord row = upload(userId, session, 6);
        fileStorageService.deleteByWork(session.getWorkId());
        assertEquals(0, fileCount(session));
        assertEquals(6, userMapper.selectById(userId).getUsedBytes());
        assertTrue(Files.isRegularFile(diskPath(row)));
        TestTransaction.flagForCommit();
        TestTransaction.end();
        assertTrue(Files.notExists(diskPath(row)));
        assertEquals(0, userMapper.selectById(userId).getUsedBytes());
        assertEquals(0, workMapper.selectById(session.getWorkId()).getSizeBytes());
    }

    @Test
    void hardDeleteRollbackPreservesDirectory() throws Exception {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        FileRecord row = upload(userId, session, 6);
        Path generated = Path.of(dataRoot, "works", session.getWorkId(), "generated.txt");
        Files.writeString(generated, "generated");
        TestTransaction.flagForCommit();
        TestTransaction.end();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            workService.hardDelete(userId, session.getWorkId());
            assertNull(workMapper.selectById(session.getWorkId()));
            assertTrue(Files.isRegularFile(generated));
            status.setRollbackOnly();
        });
        assertNotNull(workMapper.selectById(session.getWorkId()));
        assertEquals(1, fileCount(session));
        assertTrue(Files.isRegularFile(diskPath(row)));
        assertTrue(Files.isRegularFile(generated));
        workService.hardDelete(userId, session.getWorkId());
        assertTrue(Files.notExists(generated.getParent()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentWorkUploadsRespectQuota() throws Exception {
        concurrentUploads(1024, 8);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentUserUploadsRespectQuota() throws Exception {
        concurrentUploads(8, 1024);
    }

    private void concurrentUploads(long quota, long workQuota) throws Exception {
        Long userId = insertUser(quota);
        User update = new User();
        update.setId(userId);
        update.setWorkQuotaBytes(workQuota);
        userMapper.updateById(update);
        Session session = insertSession(userId);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> uploadStatus(userId, session, start));
            var second = pool.submit(() -> uploadStatus(userId, session, start));
            start.countDown();
            List<Integer> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(code -> code == 200).count());
            assertEquals(1, results.stream().filter(code -> code == 409).count());
        }
        assertEquals(1, fileCount(session));
        assertEquals(6, userMapper.selectById(userId).getUsedBytes());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDeletesSubtractOnlyOnce() throws Exception {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        FileRecord row = upload(userId, session, 6);
        upload(userId, session, 6);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> deleteStatus(userId, row, start));
            var second = pool.submit(() -> deleteStatus(userId, row, start));
            start.countDown();
            List<Integer> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertTrue(results.contains(200));
            assertTrue(results.contains(404));
        }
        assertEquals(1, fileCount(session));
        assertEquals(6, userMapper.selectById(userId).getUsedBytes());
        assertTrue(Files.notExists(diskPath(row)));
    }

    @Test
    void reconcileOnlyDropsMissingRowsAndRepairsCounter() throws Exception {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        FileRecord missing = upload(userId, session, 6);
        upload(userId, session, 4);
        Files.delete(diskPath(missing));
        userMapper.updateUsedBytes(userId, 99L);
        assertEquals(1, reconcileTask.reconcileUser(userId));
        assertEquals(1, fileCount(session));
        assertEquals(4, userMapper.selectById(userId).getUsedBytes());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reconcileWaitsForUploadCommit() throws Exception {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var uploading = pool.submit(() -> fileStorageService.upload(userId, session.getId(),
                    "a.png", "image/png", 6, blockingContent(written, release)));
            try {
                assertTrue(written.await(10, TimeUnit.SECONDS));
                var reconciling = pool.submit(() -> reconcileTask.reconcileUser(userId));
                assertThrows(TimeoutException.class, () -> reconciling.get(200, TimeUnit.MILLISECONDS));
                release.countDown();
                uploading.get(10, TimeUnit.SECONDS);
                assertEquals(0, reconciling.get(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
        assertEquals(1, fileCount(session));
        assertEquals(6, userMapper.selectById(userId).getUsedBytes());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void orphanSweepWaitsForUploadCommit() throws Exception {
        Long userId = insertUser(1024);
        Session session = insertSession(userId);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var uploading = pool.submit(() -> fileStorageService.upload(userId, session.getId(),
                    "a.png", "image/png", 6, blockingContent(written, release)));
            try {
                assertTrue(written.await(10, TimeUnit.SECONDS));
                Path images = Path.of(dataRoot, "works", session.getWorkId(), session.getId(), "images");
                String key;
                try (var files = Files.list(images)) {
                    key = Path.of(dataRoot).relativize(files.findFirst().orElseThrow()).toString().replace('\\', '/');
                }
                var sweeping = pool.submit(() -> reconcileTask.deleteOrphan("local", fileStorageService.currentStorage(), key));
                assertThrows(TimeoutException.class, () -> sweeping.get(200, TimeUnit.MILLISECONDS));
                release.countDown();
                FileRecord row = uploading.get(10, TimeUnit.SECONDS);
                assertFalse(sweeping.get(10, TimeUnit.SECONDS));
                assertTrue(Files.isRegularFile(diskPath(row)));
            } finally {
                release.countDown();
            }
        }
        assertEquals(1, fileCount(session));
    }

    private java.io.InputStream blockingContent(CountDownLatch written, CountDownLatch release) {
        return new java.io.InputStream() {
            private final ByteArrayInputStream content = new ByteArrayInputStream(new byte[6]);

            @Override
            public int read() {
                return content.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                int read = content.read(bytes, offset, length);
                if (read == -1) {
                    written.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("上传等待超时");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return read;
            }
        };
    }

    private int uploadStatus(Long userId, Session session, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            upload(userId, session, 6);
            return 200;
        } catch (BizException e) {
            return e.getStatus();
        }
    }

    private int deleteStatus(Long userId, FileRecord row, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            fileStorageService.delete(userId, row.getId());
            return 200;
        } catch (BizException e) {
            return e.getStatus();
        }
    }

    private FileRecord upload(Long userId, Session session, int size) {
        return fileStorageService.upload(userId, session.getId(), "a.png", "image/png", size,
                new ByteArrayInputStream(new byte[size]));
    }

    private long fileCount(Session session) {
        return fileRecordMapper.selectCount(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getSessionId, session.getId()));
    }

    private Long insertUser(long quotaBytes) {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setQuotaBytes(quotaBytes);
        userMapper.insert(user);
        userIds.add(user.getId());
        return user.getId();
    }

    private Session insertSession(Long userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Work work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(userId);
        work.setName("t");
        work.setSizeBytes(0L);
        work.setIsDefault(false);
        work.setCreatedAt(now);
        work.setUpdatedAt(now);
        workMapper.insert(work);
        workIds.add(work.getId());
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setWorkId(work.getId());
        session.setCurrentSegment(0);
        session.setStatus(Session.STATUS_ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        return session;
    }

    private Path diskPath(FileRecord row) {
        return Path.of(dataRoot).resolve(row.getPath());
    }
}
