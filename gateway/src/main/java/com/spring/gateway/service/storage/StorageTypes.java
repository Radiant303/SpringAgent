package com.spring.gateway.service.storage;

import java.util.Set;

/**
 * 存储类型常量：取值与存储实现 Bean 名一一对应。
 *
 * @author hanbing
 * @since 2026-10-14
 */
public final class StorageTypes {

    /** 本地服务器磁盘。 */
    public static final String LOCAL = "local";

    /** 阿里云 OSS。 */
    public static final String ALIYUN_OSS = "aliyun_oss";

    /** 全部合法取值。 */
    public static final Set<String> ALL = Set.of(LOCAL, ALIYUN_OSS);

    private StorageTypes() {
    }
}
