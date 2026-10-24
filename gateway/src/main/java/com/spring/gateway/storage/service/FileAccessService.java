package com.spring.gateway.storage.service;

import java.nio.file.Path;

/**
 * 文件访问分发业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface FileAccessService {
    Access open(String fileId, boolean accelCapable);

    enum AccessKind {
        DIRECT,
        ACCEL,
        LOCAL
    }

    record Access(AccessKind kind, String location, Path path, String mimeType, long sizeBytes) {
    }
}
