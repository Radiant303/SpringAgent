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
import com.spring.gateway.service.storage.ObjectStorageService;
import com.spring.gateway.service.storage.StorageException;
import com.spring.gateway.service.storage.StorageKeys;
import com.spring.gateway.service.storage.StorageServiceSelector;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 上传文件的存储及配额管理。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** 单文件大小上限，单位字节。 */
    public static final long MAX_FILE_BYTES = 100L * 1024 * 1024;

    private final FileRecordMapper fileRecordMapper;
    private final SessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final WorkMapper workMapper;
    private final SystemSettingService systemSettingService;
    private final StorageServiceSelector storageSelector;
    private final SnowflakeIdGenerator idGenerator;
    private final UserStorageService userStorageService;

    /**
     * 上传文件，用户空间或单工作区配额不足时拒绝。
     *
     * @param userId 当前用户 ID
     * @param sessionId 目标会话 ID
     * @param originalFilename 原始文件名
     * @param mimeType 内容类型
     * @param size 字节数
     * @param content 内容流
     * @return 文件记录
     * @throws BizException 会话不存在（404）、文件为空或超限（400）、配额不足（409）
     */
    @Transactional
    public FileRecord upload(Long userId, String sessionId, String originalFilename,
                             String mimeType, long size, InputStream content) {
        if (size <= 0) {
            throw new BizException(400, "文件内容为空");
        }
        if (size > MAX_FILE_BYTES) {
            throw new BizException(400, "单文件不能超过 " + (MAX_FILE_BYTES / 1024 / 1024) + " MB");
        }
        User user = userMapper.selectForUpdate(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getId, sessionId).last("FOR UPDATE"));
        if (session == null || !userId.equals(session.getUserId())
                || Session.STATUS_DELETED.equals(session.getStatus())) {
            throw new BizException(404, "会话不存在");
        }
        Work work = workMapper.selectForUpdate(session.getWorkId());
        if (work == null || !userId.equals(work.getUserId())) {
            throw new BizException(404, "项目不存在");
        }
        UserStorageService.Snapshot baseline = userStorageService.snapshot(user);
        precheckQuota(user, baseline, work.getId(), size);
        String ext = sanitizeExt(originalFilename);
        long fileId = idGenerator.nextId();
        String key = StorageKeys.imageKey(work.getId(), sessionId, String.valueOf(fileId), ext);
        String storageType = systemSettingService.getStorageType();
        ObjectStorageService storage = storageSelector.get(storageType);
        userStorageService.beginChange(userId);
        String storedKey;
        try {
            storedKey = storage.upload(content, size, key, sanitizeMime(mimeType));
        } catch (RuntimeException e) {
            deleteQuietly(storage, key);
            throw e;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        userStorageService.cleanupUpload(userId, work.getId(), storedKey, storage);
                    } catch (RuntimeException e) {
                        log.warn("上传回滚清理失败", e);
                    }
                }
            }
        });
        long storedSize = size;
        Path local = storage.localPath(storedKey).orElse(null);
        if (local != null) {
            try {
                storedSize = Files.size(local);
            } catch (IOException e) {
                throw new StorageException("上传文件大小读取失败", e);
            }
            if (storedSize <= 0 || storedSize > MAX_FILE_BYTES) {
                throw new BizException(400, "上传文件实际大小无效");
            }
            precheckQuota(user, baseline, work.getId(), storedSize);
        }

        FileRecord row = new FileRecord();
        row.setId(fileId);
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setStorageType(storageType);
        row.setPath(storedKey);
        row.setSizeBytes(storedSize);
        row.setMimeType(sanitizeMime(mimeType));
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        fileRecordMapper.insert(row);
        userStorageService.addWorkBytes(userId, work.getId(), storedSize);
        return row;
    }

    /**
     * 按 ID 查询文件记录。
     *
     * @param fileId 文件 ID
     * @return 文件记录
     * @throws BizException 不存在（404）
     */
    public FileRecord requireById(long fileId) {
        FileRecord row = fileRecordMapper.selectById(fileId);
        if (row == null) {
            throw new BizException(404, "文件不存在");
        }
        return row;
    }

    /**
     * 删除当前用户的文件。
     *
     * @param userId 当前用户 ID
     * @param fileId 文件 ID
     * @throws BizException 不存在或不属于当前用户（404）
     */
    @Transactional
    public void delete(Long userId, long fileId) {
        User user = userMapper.selectForUpdate(userId);
        if (user == null) throw new BizException(404, "用户不存在");
        userStorageService.ensureCurrent(user);
        FileRecord row = fileRecordMapper.selectOne(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getId, fileId).last("FOR UPDATE"));
        if (row == null || !userId.equals(row.getUserId())
                || fileRecordMapper.deleteById(row.getId()) == 0) {
            throw new BizException(404, "文件不存在");
        }
        if (!"local".equals(row.getStorageType())) {
            userStorageService.addWorkBytes(userId, row.getPath().split("/", 3)[1], -row.getSizeBytes());
        }
        deleteAfterCommit(row);
    }

    /**
     * 清理工作区上传文件及占用计数。
     *
     * @param workId work ID
     */
    @Transactional
    public void deleteByWork(String workId) {
        Work work = workMapper.selectById(workId);
        if (work == null) {
            return;
        }
        User user = userMapper.selectForUpdate(work.getUserId());
        if (user == null) return;
        userStorageService.ensureCurrent(user);
        if (workMapper.selectForUpdate(workId) == null) {
            return;
        }
        List<Session> sessions = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(Session::getWorkId, workId).last("FOR UPDATE"));
        if (sessions.isEmpty()) {
            return;
        }
        List<String> sessionIds = sessions.stream().map(Session::getId).toList();
        List<FileRecord> rows = fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getUserId, work.getUserId())
                .in(FileRecord::getSessionId, sessionIds).last("FOR UPDATE"));
        for (FileRecord row : rows) {
            if (fileRecordMapper.deleteById(row.getId()) > 0) {
                if (!"local".equals(row.getStorageType())) {
                    userStorageService.addWorkBytes(row.getUserId(), workId, -row.getSizeBytes());
                }
                deleteAfterCommit(row);
            }
        }
    }

    @Transactional
    public void deleteBySession(Long userId, String workId, String sessionId) {
        List<FileRecord> rows = fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getUserId, userId).eq(FileRecord::getSessionId, sessionId).last("FOR UPDATE"));
        for (FileRecord row : rows) {
            if (fileRecordMapper.deleteById(row.getId()) > 0 && !"local".equals(row.getStorageType())) {
                userStorageService.addWorkBytes(userId, workId, -row.getSizeBytes());
                deleteAfterCommit(row);
            }
        }
    }

    private void deleteAfterCommit(FileRecord row) {
        Path marker = "local".equals(row.getStorageType())
                ? userStorageService.beginDeferredChange(row.getUserId()) : null;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ObjectStorageService storage = storageSelector.get(row.getStorageType());
                    userStorageService.cleanupUpload(row.getUserId(), row.getPath().split("/", 3)[1], row.getPath(), storage, marker);
                } catch (RuntimeException e) {
                    log.warn("文件删除失败，留孤儿待对账: key={}", row.getPath(), e);
                }
            }
        });
    }

    /** 当前启用的存储实现 */
    public ObjectStorageService currentStorage() {
        return storageSelector.get(systemSettingService.getStorageType());
    }

    private void precheckQuota(User user, UserStorageService.Snapshot snapshot, String workId, long size) {
        long quota = user.getQuotaBytes() == null ? 0L : user.getQuotaBytes();
        long workUsed = snapshot.workBytes(workId);
        if (snapshot.usedBytes() > quota - size) {
            throw new BizException(409, "存储空间不足：上传后将超出配额，请先清理文件");
        }
        long workMax = user.getWorkQuotaBytes() != null
                ? user.getWorkQuotaBytes() : systemSettingService.getWorkMaxBytes();
        if (workMax > 0 && workUsed > workMax - size) {
            throw new BizException(409, "单工作区配额不足：上传后将超出上限，请先清理文件");
        }
    }

    /** 扩展名白名单化：只留字母数字，最长 10 位，缺省 bin */
    private static String sanitizeExt(String originalFilename) {
        String ext = "";
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            if (dot >= 0 && dot < originalFilename.length() - 1) {
                ext = originalFilename.substring(dot + 1);
            }
        }
        ext = ext.replaceAll("[^A-Za-z0-9]", "");
        if (ext.isEmpty() || ext.length() > 10) {
            return "bin";
        }
        return ext.toLowerCase();
    }

    /** mime 白名单化：必须是 type/subtype 形态，否则落 application/octet-stream */
    private static String sanitizeMime(String mimeType) {
        if (mimeType != null && mimeType.matches("[\\w.+-]{1,64}/[\\w.+-]{1,64}")) {
            return mimeType;
        }
        return "application/octet-stream";
    }

    private static void deleteQuietly(ObjectStorageService storage, String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("上传补偿删除失败，留孤儿待对账: key={}", key, e);
        }
    }
}
