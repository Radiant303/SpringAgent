package com.spring.gateway.storage.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.storage.entity.FileRecord;
import com.spring.gateway.storage.infrastructure.StorageServiceSelector;
import com.spring.gateway.storage.service.FileAccessService;
import com.spring.gateway.storage.service.FileStorageService;
import com.spring.gateway.storage.service.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 文件访问分发实现。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Service
@RequiredArgsConstructor
public class FileAccessServiceImpl implements FileAccessService {
    private final FileStorageService fileStorageService;
    private final StorageServiceSelector storageSelector;

    @Override
    public Access open(String fileId, boolean accelCapable) {
        long id;
        try {
            id = Long.parseLong(fileId);
        } catch (NumberFormatException e) {
            throw new BizException(404, "文件不存在");
        }
        FileRecord row = fileStorageService.requireById(id);
        ObjectStorageService storage = storageSelector.get(row.getStorageType());
        Optional<String> direct = storage.directUrl(row.getPath());
        if (direct.isPresent()) {
            return new Access(AccessKind.DIRECT, direct.get(), null, row.getMimeType(), row.getSizeBytes());
        }
        Path path = storage.localPath(row.getPath())
                .orElseThrow(() -> new BizException(502, "该存储类型不支持本地分发"));
        if (!Files.isRegularFile(path)) {
            throw new BizException(404, "文件不存在");
        }
        if (accelCapable) {
            return new Access(AccessKind.ACCEL, "/_internal/" + row.getPath(), null,
                    row.getMimeType(), row.getSizeBytes());
        }
        return new Access(AccessKind.LOCAL, null, path, row.getMimeType(), row.getSizeBytes());
    }
}
