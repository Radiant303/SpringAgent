package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.entity.FileRecord;
import com.spring.gateway.entity.User;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.FileRecordMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.mapper.WorkMapper;
import com.spring.gateway.service.storage.ObjectStorageService;
import com.spring.gateway.service.storage.StorageException;
import com.spring.gateway.service.storage.StorageKeys;
import com.spring.gateway.service.storage.StorageServiceSelector;
import com.spring.gateway.service.storage.StorageTypes;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

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

    /** 孤儿对象的保留时长，单位毫秒。 */
    @org.springframework.beans.factory.annotation.Value("${app.storage.orphan-grace-ms:3600000}")
    private long orphanGraceMs;

    public void reconcile() {
        try {
            int missing = 0;
            List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>().select(User::getId));
            for (User user : users) {
                missing += reconcileUser(user.getId());
            }
            int orphans = sweepOrphans();
            if (missing > 0 || orphans > 0) {
                log.info("存储对账完成：清理丢失记录 {} 条、孤儿文件 {} 个", missing, orphans);
            }
        } catch (Exception e) {
            log.warn("存储对账失败，下轮重试", e);
        }
    }

    int reconcileUser(Long userId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            if (userMapper.selectForUpdate(userId) == null) {
                return 0;
            }
            ObjectStorageService local = storageSelector.get(StorageTypes.LOCAL);
            List<FileRecord> rows = fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                    .eq(FileRecord::getUserId, userId).last("FOR UPDATE"));
            int dropped = 0;
            long used = 0L;
            for (FileRecord row : rows) {
                boolean missing = StorageTypes.LOCAL.equals(row.getStorageType())
                        && local.localPath(row.getPath()).map(path -> Files.notExists(path)).orElse(false);
                if (missing) {
                    dropped += fileRecordMapper.deleteById(row.getId());
                } else {
                    used += row.getSizeBytes();
                }
            }
            userMapper.updateUsedBytes(userId, used);
            return dropped;
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
            storage.delete(key);
            return true;
        }));
    }
}
