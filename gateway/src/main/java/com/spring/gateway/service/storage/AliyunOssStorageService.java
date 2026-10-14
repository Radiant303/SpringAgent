package com.spring.gateway.service.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.spring.gateway.service.SystemSettingService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 阿里云 OSS 存储：连接信息存 system_settings（站长后台可改），
 * 客户端按配置指纹缓存，配置一变即重建。访问走签名 URL 302 跳转。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@Service(StorageTypes.ALIYUN_OSS)
public class AliyunOssStorageService implements ObjectStorageService {

    /** 签名 URL 有效期：1 小时（模型厂商抓取与浏览器访问都足够）。 */
    private static final long SIGN_URL_TTL_MS = 3_600_000L;

    private final SystemSettingService systemSettingService;

    /** 当前生效配置与客户端；配置变化时整体替换。 */
    private volatile CachedClient cached;

    public AliyunOssStorageService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    @Override
    public String upload(InputStream content, long size, String key, String mimeType) {
        CachedClient client = client();
        try {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(size);
            if (mimeType != null && !mimeType.isBlank()) {
                meta.setContentType(mimeType);
            }
            client.oss.putObject(client.config.bucket(), key, content, meta);
        } catch (OSSException | ClientException e) {
            throw new StorageException("OSS 上传失败: " + e.getMessage(), e);
        }
        return key;
    }

    @Override
    public void delete(String key) {
        CachedClient client = client();
        try {
            client.oss.deleteObject(client.config.bucket(), key);
        } catch (OSSException | ClientException e) {
            throw new StorageException("OSS 删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> directUrl(String key) {
        CachedClient client = client();
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                    client.config.bucket(), key);
            request.setExpiration(new Date(System.currentTimeMillis() + SIGN_URL_TTL_MS));
            URL url = client.oss.generatePresignedUrl(request);
            return Optional.of(url.toString());
        } catch (OSSException | ClientException e) {
            throw new StorageException("OSS 签名 URL 生成失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredObject> listObjects() {
        OssConfig config = readConfig();
        if (!config.complete()) {
            return List.of();
        }
        CachedClient client = client();
        List<StoredObject> objects = new ArrayList<>();
        try {
            String marker = null;
            do {
                // 只列受管前缀，bucket 里的其他内容不进对账
                ListObjectsRequest request = new ListObjectsRequest(client.config.bucket())
                        .withPrefix("works/").withMaxKeys(1000).withMarker(marker);
                ObjectListing listing = client.oss.listObjects(request);
                for (OSSObjectSummary summary : listing.getObjectSummaries()) {
                    if (StorageKeys.isManaged(summary.getKey())) {
                        objects.add(new StoredObject(summary.getKey(),
                                summary.getLastModified().toInstant()));
                    }
                }
                marker = listing.isTruncated() ? listing.getNextMarker() : null;
            } while (marker != null);
        } catch (OSSException | ClientException e) {
            throw new StorageException("OSS 列举失败: " + e.getMessage(), e);
        }
        return objects;
    }

    /** 当前配置完整才返回 true（对账任务据此决定是否扫 OSS 孤儿） */
    public boolean configured() {
        return readConfig().complete();
    }

    /** 取当前配置的客户端；配置变化时重建并关闭旧客户端 */
    private CachedClient client() {
        OssConfig config = readConfig();
        if (!config.complete()) {
            throw new StorageException("阿里云 OSS 未配置完整，请到站长后台补齐");
        }
        CachedClient current = cached;
        if (current != null && current.config.equals(config)) {
            return current;
        }
        synchronized (this) {
            if (cached != null && cached.config.equals(config)) {
                return cached;
            }
            OSS oss = new OSSClientBuilder().build(
                    config.endpoint(), config.accessKeyId(), config.accessKeySecret());
            CachedClient previous = cached;
            cached = new CachedClient(config, oss);
            if (previous != null) {
                previous.oss.shutdown();
            }
            return cached;
        }
    }

    private OssConfig readConfig() {
        return new OssConfig(
                systemSettingService.getOssEndpoint(),
                systemSettingService.getOssBucket(),
                systemSettingService.getOssAccessKeyId(),
                systemSettingService.getOssAccessKeySecret());
    }

    @PreDestroy
    void shutdown() {
        CachedClient current = cached;
        if (current != null) {
            current.oss.shutdown();
        }
    }

    /** OSS 连接配置；complete() = 四项齐全 */
    record OssConfig(String endpoint, String bucket, String accessKeyId, String accessKeySecret) {
        boolean complete() {
            return !endpoint.isBlank() && !bucket.isBlank()
                    && !accessKeyId.isBlank() && !accessKeySecret.isBlank();
        }
    }

    private record CachedClient(OssConfig config, OSS oss) {
    }
}
