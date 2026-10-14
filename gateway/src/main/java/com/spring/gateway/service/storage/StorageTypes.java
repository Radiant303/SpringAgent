package com.spring.gateway.service.storage;

import java.util.Set;

/**
 * 存储类型常量：值与 {@link ObjectStorageService} 实现类的 Bean 名、
 * files.storage_type 列值、system_settings 的 storage.type 配置值严格一一对应。
 * 新增厂商 = 新实现类（Bean 名取新常量）+ 常量登记，业务代码零改动。
 *
 * @author hanbing
 * @since 2026-10-14
 */
public final class StorageTypes {

    /** 本地服务器磁盘（data-root 下）。 */
    public static final String LOCAL = "local";

    /** 阿里云 OSS。 */
    public static final String ALIYUN_OSS = "aliyun_oss";

    /** 全部合法取值（配置校验用）。 */
    public static final Set<String> ALL = Set.of(LOCAL, ALIYUN_OSS);

    private StorageTypes() {
    }
}
