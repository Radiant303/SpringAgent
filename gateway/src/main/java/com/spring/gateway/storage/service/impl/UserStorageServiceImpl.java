package com.spring.gateway.storage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.storage.entity.FileRecord;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.workspace.entity.Work;
import com.spring.gateway.storage.mapper.FileRecordMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import com.spring.gateway.storage.infrastructure.StorageException;
import com.spring.gateway.storage.infrastructure.StorageTypes;
import com.spring.gateway.storage.service.ObjectStorageService;
import com.spring.gateway.storage.service.UserStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户文件工作区的增量计量。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Service
@RequiredArgsConstructor
public class UserStorageServiceImpl implements UserStorageService {
    private static final Logger log = LoggerFactory.getLogger(UserStorageServiceImpl.class);
    private final WorkMapper workMapper;
    private final SessionMapper sessionMapper;
    private final FileRecordMapper fileRecordMapper;
    private final UserMapper userMapper;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.data-root}")
    private String dataRoot;

    public Snapshot snapshot(User requested) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            User user = userMapper.selectForUpdate(requested.getId());
            if (user == null) {
                throw new StorageException("用户身份无效");
            }
            ensureCurrent(user);
            Map<String, WorkUsage> usage = new LinkedHashMap<>();
            for (Work work : works(user.getId())) {
                usage.put(work.getId(), new WorkUsage(work.getSizeBytes()));
            }
            return new Snapshot(user.getUsedBytes(), Map.copyOf(usage));
        });
    }

    public void ensureCurrent(User user) {
        boolean pending = pendingChanges(user.getId()).stream().anyMatch(marker ->
                TransactionSynchronizationManager.getSynchronizations().stream()
                        .noneMatch(sync -> sync instanceof PendingChange change && change.markers.contains(marker)));
        if (!Boolean.TRUE.equals(user.getStorageInitialized()) || pending) {
            reconcileLocked(user);
        }
    }

    public int reconcileLocked(User user) {
        List<Work> works = works(user.getId());
        Map<String, String> sessionWorks = new HashMap<>();
        for (Session session : sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(Session::getUserId, user.getId()).last("FOR UPDATE"))) {
            sessionWorks.put(session.getId(), session.getWorkId());
        }
        Map<String, Long> remote = new HashMap<>();
        int dropped = 0;
        for (FileRecord file : fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getUserId, user.getId()).last("FOR UPDATE"))) {
            if (StorageTypes.LOCAL.equals(file.getStorageType())) {
                Path relative = Path.of(file.getPath()).normalize();
                if (relative.isAbsolute() || relative.startsWith("..") || !relative.startsWith("works")) {
                    throw new StorageException("上传文件路径无效");
                }
                BasicFileAttributes attributes = checkedAttributes(root().resolve(relative));
                if (attributes == null) {
                    dropped += fileRecordMapper.deleteById(file.getId());
                } else if (!attributes.isRegularFile()) {
                    throw new StorageException("上传文件需要为普通文件");
                } else if (file.getSizeBytes() != attributes.size()) {
                    file.setSizeBytes(attributes.size());
                    fileRecordMapper.updateById(file);
                }
            } else {
                String workId = sessionWorks.get(file.getSessionId());
                if (workId != null) remote.merge(workId, file.getSizeBytes(), Math::addExact);
            }
        }
        long total = 0;
        for (Work work : works) {
            TreeUsage tree = workTreeBytes(work.getId());
            long size = Math.addExact(tree.totalBytes(), remote.getOrDefault(work.getId(), 0L));
            workMapper.updateStorage(work.getId(), size, tree.internalBytes());
            total = Math.addExact(total, size);
        }
        long knowledge = knowledgeBytes(user.getId());
        user.setUsedBytes(Math.addExact(total, knowledge));
        user.setKnowledgeBytes(knowledge);
        user.setStorageInitialized(true);
        userMapper.updateStorage(user.getId(), user.getUsedBytes(), knowledge, true);
        clearAfterCommit(pendingChanges(user.getId()));
        return dropped;
    }

    public void addWorkBytes(Long userId, String workId, long delta) {
        User user = userMapper.selectForUpdate(userId);
        Work work = workMapper.selectForUpdate(workId);
        if (user == null || work == null || !userId.equals(work.getUserId())) {
            throw new StorageException("文件资源归属无效");
        }
        long total = Math.addExact(user.getUsedBytes(), delta);
        long size = Math.addExact(work.getSizeBytes(), delta);
        if (total < 0 || size < 0) throw new StorageException("存储计数需要重新核算");
        userMapper.updateStorage(userId, total, user.getKnowledgeBytes(), true);
        workMapper.updateStorage(workId, size, work.getInternalBytes());
    }

    public void deleteSessionDirectory(Long userId, String workId, String sessionId) {
        User user = userMapper.selectForUpdate(userId);
        Work work = workMapper.selectForUpdate(workId);
        if (user == null || work == null || !userId.equals(work.getUserId())) {
            throw new StorageException("会话文件资源归属无效");
        }
        if (!UUID.fromString(workId).toString().equals(workId)
                || !UUID.fromString(sessionId).toString().equals(sessionId)) {
            throw new StorageException("会话目录标识无效");
        }
        Path directory = root().resolve("works").resolve(workId).resolve(sessionId);
        BasicFileAttributes attributes = checkedAttributes(directory);
        if (attributes == null) return;
        if (!attributes.isDirectory()) throw new StorageException("会话资源需要为普通目录");
        long size = treeBytes(directory, directory.resolve(".springagent")).totalBytes();
        beginChange(userId);
        try (var entries = Files.walk(directory)) {
            List<Path> paths = entries.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                checkedAttributes(path);
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new StorageException("会话目录删除失败，请稍后重试", e);
        }
        addWorkBytes(userId, workId, -size);
    }

    public void releaseWork(User user, String workId) {
        ensureCurrent(user);
        Work work = workMapper.selectForUpdate(workId);
        if (work != null) addWorkBytes(user.getId(), workId, -work.getSizeBytes());
    }

    public Path beginChange(Long userId) {
        return beginChange(userId, true);
    }

    public Path beginDeferredChange(Long userId) {
        return beginChange(userId, false);
    }

    private Path beginChange(Long userId, boolean clearOnCommit) {
        Path pending = pendingRoot(userId);
        try {
            Files.createDirectories(pending);
            checkedAttributes(pending);
            Path marker = pending.resolve(UUID.randomUUID() + ".pending");
            try (FileChannel file = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                file.write(ByteBuffer.wrap(new byte[]{1}));
                file.force(true);
            }
            TransactionSynchronizationManager.registerSynchronization(new PendingChange(List.of(marker), clearOnCommit));
            return marker;
        } catch (IOException e) {
            throw new StorageException("存储变更标记写入失败", e);
        }
    }

    public void cleanupLocal(Long userId, String workId, String key, ObjectStorageService storage) {
        cleanupLocal(userId, workId, key, storage, null);
    }

    private void cleanupLocal(Long userId, String workId, String key, ObjectStorageService storage, Path marker) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            if (marker != null) clearAfterCommit(List.of(marker));
            removeLocalLocked(userId, workId, key, storage);
        });
    }

    public void removeLocalLocked(Long userId, String workId, String key, ObjectStorageService storage) {
        User user = userMapper.selectForUpdate(userId);
        Work work = workMapper.selectForUpdate(workId);
        if (user == null || work == null) {
            storage.delete(key);
            return;
        }
        if (!userId.equals(work.getUserId())) throw new StorageException("文件资源归属无效");
        ensureCurrent(user);
        Path path = storage.localPath(key).orElseThrow();
        BasicFileAttributes attributes = checkedAttributes(path);
        if (attributes != null) {
            if (!attributes.isRegularFile()) throw new StorageException("上传文件需要为普通文件");
            beginChange(userId);
            storage.delete(key);
            addWorkBytes(userId, workId, -attributes.size());
        } else {
            reconcileLocked(user);
        }
    }

    public void cleanupUpload(Long userId, String workId, String key, ObjectStorageService storage) {
        cleanupUpload(userId, workId, key, storage, null);
    }

    public void cleanupUpload(Long userId, String workId, String key, ObjectStorageService storage, Path marker) {
        if (storage.localPath(key).isPresent()) cleanupLocal(userId, workId, key, storage, marker);
        else storage.delete(key);
    }

    private List<Work> works(Long userId) {
        return workMapper.selectList(new LambdaQueryWrapper<Work>().eq(Work::getUserId, userId).last("FOR UPDATE"));
    }

    private Path root() {
        return Path.of(dataRoot).toAbsolutePath().normalize();
    }

    private Path pendingRoot(Long userId) {
        Path pending = root().resolve("users").resolve(userId.toString()).resolve(".storage-pending");
        checkedAttributes(pending);
        return pending;
    }

    private List<Path> pendingChanges(Long userId) {
        Path pending = pendingRoot(userId);
        if (checkedAttributes(pending) == null) return List.of();
        try (var files = Files.list(pending)) {
            List<Path> markers = files.toList();
            for (Path marker : markers) {
                BasicFileAttributes attributes = checkedAttributes(marker);
                if (attributes == null || !attributes.isRegularFile()) throw new StorageException("存储标记状态无效");
            }
            return markers;
        } catch (IOException e) {
            throw new StorageException("存储变更标记读取失败", e);
        }
    }

    private static void clearAfterCommit(List<Path> markers) {
        if (!markers.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new PendingChange(markers, true));
        }
    }

    private record PendingChange(List<Path> markers, boolean clearOnCommit) implements TransactionSynchronization {
        @Override
        public void afterCommit() {
            if (!clearOnCommit) return;
            for (Path marker : markers) {
                try {
                    checkedAttributes(marker);
                    Files.deleteIfExists(marker);
                } catch (IOException | StorageException e) {
                    log.warn("存储标记清理失败", e);
                }
            }
        }
    }

    public Path knowledgeRoot(long userId) {
        if (userId <= 0) throw new StorageException("用户身份无效");
        return root().resolve("users").resolve(Long.toString(userId)).resolve("knowledge");
    }

    public long knowledgeBytes(long userId) {
        long total = 0L;
        for (String name : List.of("declarative.md", "procedural.md", "conditional.md")) {
            BasicFileAttributes attributes = checkedAttributes(knowledgeRoot(userId).resolve(name));
            if (attributes != null) {
                if (!attributes.isRegularFile()) throw new StorageException("知识文档需要为普通文件");
                total = Math.addExact(total, attributes.size());
            }
        }
        return total;
    }

    public long directoryBytes(String workId) {
        return workTreeBytes(workId).totalBytes();
    }

    private TreeUsage workTreeBytes(String workId) {
        Path directory = root().resolve("works").resolve(workId);
        BasicFileAttributes attributes = checkedAttributes(directory);
        if (attributes == null) return new TreeUsage(0, 0);
        if (!attributes.isDirectory()) throw new StorageException("工作区需要为普通目录");
        return treeBytes(directory, directory.resolve(".springagent"));
    }

    private static TreeUsage treeBytes(Path root, Path internalRoot) {
        long total = 0L;
        long internal = 0L;
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                BasicFileAttributes attributes = checkedAttributes(path);
                if (attributes == null) continue;
                if (path.equals(internalRoot) && !attributes.isDirectory()) {
                    throw new StorageException("内部工作区需要为普通目录");
                }
                TreeUsage child = attributes.isDirectory()
                        ? treeBytes(path, internalRoot) : new TreeUsage(attributes.size(), 0);
                total = Math.addExact(total, child.totalBytes());
                internal = Math.addExact(internal, path.equals(internalRoot) ? child.totalBytes() : child.internalBytes());
            }
            return new TreeUsage(total, internal);
        } catch (IOException | SecurityException e) {
            throw new StorageException("工作区大小读取失败", e);
        }
    }

    private record TreeUsage(long totalBytes, long internalBytes) { }

    private static BasicFileAttributes checkedAttributes(Path target) {
        Path current = target.getRoot();
        BasicFileAttributes attributes = null;
        try {
            for (Path segment : target) {
                current = current.resolve(segment);
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()
                        || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))
                        || (!current.equals(target) && !attributes.isDirectory())
                        || (current.equals(target) && !attributes.isDirectory() && !attributes.isRegularFile())) {
                    throw new StorageException("文件资源须由普通目录和普通文件组成");
                }
            }
            return attributes;
        } catch (NoSuchFileException ignored) {
            return null;
        } catch (IOException | SecurityException e) {
            throw new StorageException("文件资源状态读取失败", e);
        }
    }

}
