package com.spring.gateway.storage.service;

import com.spring.gateway.identity.entity.User;

import java.nio.file.Path;
import java.util.Map;

/**
 * 用户存储计量业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface UserStorageService {
    Snapshot snapshot(User requested);

    void ensureCurrent(User user);

    int reconcileLocked(User user);

    void addWorkBytes(Long userId, String workId, long delta);

    void deleteSessionDirectory(Long userId, String workId, String sessionId);

    void releaseWork(User user, String workId);

    Path beginChange(Long userId);

    Path beginDeferredChange(Long userId);

    void cleanupLocal(Long userId, String workId, String key, ObjectStorageService storage);

    void removeLocalLocked(Long userId, String workId, String key, ObjectStorageService storage);

    void cleanupUpload(Long userId, String workId, String key, ObjectStorageService storage);

    void cleanupUpload(Long userId, String workId, String key, ObjectStorageService storage, Path marker);

    Path knowledgeRoot(long userId);

    long knowledgeBytes(long userId);

    long directoryBytes(String workId);

    record WorkUsage(long totalBytes) {
    }

    record Snapshot(long usedBytes, Map<String, WorkUsage> works) {
        public long workBytes(String workId) {
            WorkUsage usage = works.get(workId);
            return usage == null ? 0L : usage.totalBytes();
        }
    }
}
