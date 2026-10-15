package com.spring.gateway.service.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 对象存储接口：上传/删除/访问指路。每个存储后端一个实现类，
 * Bean 名 = {@link StorageTypes} 常量 = files.storage_type 列值 = 配置里的 storage.type。
 *
 * <p>key 约定：本地实现为相对 data-root 的路径（works/{workId}/{sessionId}/images/{fileId}.{ext}），
 * OSS 实现为 object key；files.path 只存 key，对外 URL 由网关现拼。
 *
 * @author hanbing
 * @since 2026-10-14
 */
public interface ObjectStorageService {

    /**
     * 对账视角的存量对象条目。
     *
     * @param key          存储 key
     * @param lastModified 最后修改时间（孤儿判定要宽限期，排除在途上传）
     */
    record StoredObject(String key, Instant lastModified) {
    }

    /**
     * 上传内容到指定 key
     *
     * @param content  文件内容流（实现方负责消费，不负责关闭）
     * @param size     字节数（OSS 需要预知长度）
     * @param key      存储 key
     * @param mimeType 内容类型
     * @return 实际存储 key
     * @throws StorageException 写入失败
     */
    String upload(InputStream content, long size, String key, String mimeType);

    /**
     * 删除指定 key；实现方尽力而为，失败抛 {@link StorageException} 由调用方记日志，
     * 孤儿留给定时对账清理
     *
     * @param key 存储 key
     * @throws StorageException 删除失败
     */
    void delete(String key);

    /**
     * 浏览器直达 URL（如 OSS 签名 URL）；为空表示本地存储，由 Nginx X-Accel-Redirect 分发
     *
     * @param key 存储 key
     * @return 可直接 302 的 URL
     */
    Optional<String> directUrl(String key);

    /**
     * key 对应的本地磁盘绝对路径；仅本地存储支持（网关直连开发模式下吐流兜底用）
     *
     * @param key 存储 key
     * @return 磁盘路径
     */
    default Optional<Path> localPath(String key) {
        return Optional.empty();
    }

    /**
     * 列出该后端的全部存量对象（定时对账扫孤儿用）；后端未配置就绪时返回空表
     *
     * @return 存量对象列表
     * @throws StorageException 列举失败（对账任务捕获后本轮跳过该后端）
     */
    default List<StoredObject> listObjects() {
        return List.of();
    }
}
