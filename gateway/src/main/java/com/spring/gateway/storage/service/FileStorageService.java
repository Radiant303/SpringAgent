package com.spring.gateway.storage.service;

import com.spring.gateway.storage.entity.FileRecord;

import java.io.InputStream;

/**
 * 上传文件业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface FileStorageService {
    long MAX_FILE_BYTES = 100L * 1024 * 1024;
    int MAX_BATCH_FILES = 50;

    FileRecord upload(Long userId, String sessionId, String originalFilename,
                      String mimeType, long size, InputStream content);

    FileRecord requireById(long fileId);

    void delete(Long userId, long fileId);

    void deleteByWork(String workId);

    void deleteBySession(Long userId, String workId, String sessionId);

    ObjectStorageService currentStorage();
}
