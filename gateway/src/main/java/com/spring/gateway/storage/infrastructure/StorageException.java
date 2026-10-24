package com.spring.gateway.storage.infrastructure;

/**
 * 存储后端的操作失败（不可达、凭证无效、写入失败等）。
 * 非受检：统一由 GlobalExceptionHandler 映射为 502。
 *
 * @author hanbing
 * @since 2026-10-14
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
