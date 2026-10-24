package com.spring.gateway.settings.service;

/**
 * 系统设置写入端口。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface SystemSettingWriter {
    void set(String key, String value);
}
