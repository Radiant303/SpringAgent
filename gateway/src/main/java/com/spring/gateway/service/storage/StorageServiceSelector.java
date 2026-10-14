package com.spring.gateway.service.storage;

import com.spring.gateway.common.BizException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 存储实现选择器：Spring 把全部 ObjectStorageService 实现按 Bean 名注入 Map，
 * 运行时按数据库配置的 storage.type 字符串选实现；Bean 名与配置值由 {@link StorageTypes} 收口。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@Component
public class StorageServiceSelector {

    private final Map<String, ObjectStorageService> storages;

    public StorageServiceSelector(Map<String, ObjectStorageService> storages) {
        this.storages = storages;
    }

    /**
     * 按存储类型取实现
     *
     * @param type 存储类型（{@link StorageTypes} 常量）
     * @return 存储实现
     * @throws BizException 未知类型（400）
     */
    public ObjectStorageService get(String type) {
        ObjectStorageService storage = storages.get(type);
        if (storage == null) {
            throw new BizException(400, "未知存储类型: " + type);
        }
        return storage;
    }
}
