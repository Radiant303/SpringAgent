package com.spring.gateway.storage.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.storage.entity.FileRecord;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.workspace.entity.Work;
import com.spring.gateway.storage.mapper.FileRecordMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import com.spring.gateway.storage.service.ObjectStorageService;
import com.spring.gateway.storage.service.UserStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * 上传文件及占用计数的对账任务。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@Component
@RequiredArgsConstructor
public class StorageReconcileTask {

    private static final Logger log = LoggerFactory.getLogger(StorageReconcileTask.class);

    private final FileRecordMapper fileRecordMapper;
    private final UserMapper userMapper;
    private final WorkMapper workMapper;
    private final StorageServiceSelector storageSelector;
    private final PlatformTransactionManager transactionManager;
    private final UserStorageService userStorageService;

    /** 孤儿对象的保留时长，单位毫秒。 */
    @org.springframework.beans.factory.annotation.Value("${app.storage.orphan-grace-ms:3600000}")
    private long orphanGraceMs;

    @Value("${app.data-root}")
    private String dataRoot;

    public void reconcile() {
        try {
            int missing = 0;
            List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>().select(User::getId));
            for (User user : users) {
                missing += reconcileUser(user.getId());
            }
            int orphans = sweepOrphans();
            int orphanDirs = sweepOrphanWorkDirs();
            if (missing > 0 || orphans > 0 || orphanDirs > 0) {
                log.info("存储对账完成：清理丢失记录 {} 条、孤儿文件 {} 个、孤儿 work 目录 {} 个",
                        missing, orphans, orphanDirs);
            }
        } catch (Exception e) {
            log.warn("存储对账失败，下轮重试", e);
        }
    }

    int reconcileUser(Long userId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            User user = userMapper.selectForUpdate(userId);
            if (user == null) return 0;
            return userStorageService.reconcileLocked(user);
        });
    }

    private int sweepOrphans() {
        Instant cutoff = Instant.now().minusMillis(Math.max(0L, orphanGraceMs));
        int deleted = 0;
        for (String type : StorageTypes.ALL) {
            ObjectStorageService storage = storageSelector.get(type);
            List<ObjectStorageService.StoredObject> objects;
            try {
                objects = storage.listObjects();
            } catch (StorageException e) {
                log.warn("对账列举 {} 失败，本轮跳过: {}", type, e.getMessage());
                continue;
            }
            for (ObjectStorageService.StoredObject object : objects) {
                if (!StorageKeys.isManaged(object.key()) || object.lastModified().isAfter(cutoff)) {
                    continue;
                }
                try {
                    if (deleteOrphan(type, storage, object.key())) {
                        deleted++;
                    }
                } catch (RuntimeException e) {
                    log.warn("孤儿文件删除失败: {} {}", type, object.key(), e);
                }
            }
        }
        return deleted;
    }

    /** 库行已删而磁盘仍在的 work 目录：宽限期外整个清掉 */
    private int sweepOrphanWorkDirs() {
        Path worksRoot = Path.of(dataRoot, "works");
        if (!Files.isDirectory(worksRoot)) {
            return 0;
        }
        Instant cutoff = Instant.now().minusMillis(Math.max(0L, orphanGraceMs));
        int deleted = 0;
        try (Stream<Path> dirs = Files.list(worksRoot)) {
            List<Path> candidates = dirs.filter(Files::isDirectory).toList();
            for (Path dir : candidates) {
                if (workMapper.selectById(dir.getFileName().toString()) != null) {
                    continue;
                }
                if (Files.getLastModifiedTime(dir).toInstant().isAfter(cutoff)) {
                    continue;
                }
                deleteRecursively(dir);
                deleted++;
            }
        } catch (IOException e) {
            log.warn("孤儿 work 目录扫描失败", e);
        }
        return deleted;
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted((a, b) -> -a.compareTo(b)).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // 尽力删，残留下轮再扫
                }
            });
        } catch (IOException ignored) {
            // 目录不可读视为已删除
        }
    }

    boolean deleteOrphan(String type, ObjectStorageService storage, String key) {
        if (!StorageKeys.isManaged(key)) {
            return false;
        }
        String workId = key.split("/", 3)[1];
        String filename = key.substring(key.lastIndexOf('/') + 1);
        String stem = filename.contains(".") ? filename.substring(0, filename.indexOf('.')) : filename;
        // 文件名不是纯数字就不是本代命名的文件（旧 uuid 命名等），直接按孤儿处理
        Long fileId = stem.matches("\\d{1,19}") ? Long.valueOf(stem) : null;
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            Work work = workMapper.selectById(workId);
            if (work != null) {
                userMapper.selectForUpdate(work.getUserId());
                workMapper.selectForUpdate(workId);
            }
            FileRecord row = fileId == null ? null
                    : fileRecordMapper.selectOne(new LambdaQueryWrapper<FileRecord>()
                            .eq(FileRecord::getId, fileId).eq(FileRecord::getStorageType, type)
                            .eq(FileRecord::getPath, key).last("FOR UPDATE"));
            if (row != null) {
                return false;
            }
            if (work != null && StorageTypes.LOCAL.equals(type)) {
                userStorageService.removeLocalLocked(work.getUserId(), workId, key, storage);
            } else {
                storage.delete(key);
            }
            return true;
        }));
    }
}
