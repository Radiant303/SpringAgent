package com.spring.gateway.service.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.spring.gateway.service.SystemSettingService;
import com.spring.gateway.service.SystemSettingService.OssConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 阿里云 OSS 对象存储。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Service(StorageTypes.ALIYUN_OSS)
public class AliyunOssStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(AliyunOssStorageService.class);

    /** 签名 URL 有效期（毫秒）。 */
    private static final long SIGN_URL_TTL_MS = 3_600_000L;

    private final SystemSettingService systemSettingService;
    private final Set<CachedClient> clients = new HashSet<>();
    private CachedClient cached;
    private boolean stopped;

    public AliyunOssStorageService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    @Override
    public String upload(InputStream content, long size, String key, String mimeType) {
        CachedClient client = client(systemSettingService.getOssConfig());
        try {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(size);
            if (mimeType != null && !mimeType.isBlank()) {
                meta.setContentType(mimeType);
            }
            client.oss.putObject(client.config.bucket(), key, content, meta);
            return key;
        } catch (OSSException | ClientException e) {
            throw new StorageException("OSS 上传失败");
        } finally {
            release(client);
        }
    }

    @Override
    public void delete(String key) {
        CachedClient client = client(systemSettingService.getOssConfig());
        try {
            client.oss.deleteObject(client.config.bucket(), key);
        } catch (OSSException | ClientException e) {
            throw new StorageException("OSS 删除失败");
        } finally {
            release(client);
        }
    }

    @Override
    public Optional<String> directUrl(String key) {
        CachedClient client = client(systemSettingService.getOssConfig());
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                    client.config.bucket(), key);
            request.setExpiration(new Date(System.currentTimeMillis() + SIGN_URL_TTL_MS));
            URL url = client.oss.generatePresignedUrl(request);
            return Optional.of(url.toString());
        } catch (OSSException | ClientException e) {
            throw new StorageException("OSS 签名 URL 生成失败");
        } finally {
            release(client);
        }
    }

    @Override
    public List<StoredObject> listObjects() {
        OssConfig config = systemSettingService.getOssConfig();
        if (!config.complete()) {
            return List.of();
        }
        CachedClient client = client(config);
        List<StoredObject> objects = new ArrayList<>();
        try {
            String marker = null;
            do {
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
            return objects;
        } catch (OSSException | ClientException e) {
            throw new StorageException("OSS 列举失败");
        } finally {
            release(client);
        }
    }

    public boolean configured() {
        return systemSettingService.getOssConfig().complete();
    }

    protected OSS buildClient(OssConfig config) {
        return new OSSClientBuilder().build(
                config.endpoint(), config.accessKeyId(), config.accessKeySecret());
    }

    private CachedClient client(OssConfig config) {
        CachedClient current;
        OSS closing = null;
        synchronized (this) {
            if (stopped) {
                throw new StorageException("OSS 存储已关闭");
            }
            if (!config.complete()) {
                throw new StorageException("阿里云 OSS 未配置完整，请到站长后台补齐");
            }
            if (cached == null || !cached.config.equals(config)) {
                OSS oss;
                try {
                    oss = buildClient(config);
                } catch (RuntimeException e) {
                    throw new StorageException("OSS 客户端创建失败");
                }
                CachedClient previous = cached;
                cached = new CachedClient(config, oss);
                clients.add(cached);
                if (previous != null) {
                    previous.retired = true;
                    closing = removeIfUnused(previous);
                }
            }
            current = cached;
            current.references++;
        }
        closeClient(closing);
        return current;
    }

    private void release(CachedClient client) {
        OSS closing;
        synchronized (this) {
            client.references--;
            closing = removeIfUnused(client);
        }
        closeClient(closing);
    }

    private OSS removeIfUnused(CachedClient client) {
        if (client.retired && client.references == 0 && clients.remove(client)) {
            return client.oss;
        }
        return null;
    }

    private void closeClient(OSS oss) {
        if (oss != null) {
            try {
                oss.shutdown();
            } catch (RuntimeException e) {
                log.warn("OSS 客户端关闭失败");
            }
        }
    }

    @PreDestroy
    void shutdown() {
        List<OSS> closing = new ArrayList<>();
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
            cached = null;
            for (CachedClient client : List.copyOf(clients)) {
                client.retired = true;
                OSS oss = removeIfUnused(client);
                if (oss != null) {
                    closing.add(oss);
                }
            }
        }
        for (OSS oss : closing) {
            closeClient(oss);
        }
    }

    /**
     * OSS 客户端使用状态。
     *
     * @author hanbing
     * @since 2026-10-17
     */
    private static final class CachedClient {
        private final OssConfig config;
        private final OSS oss;
        private int references;
        private boolean retired;

        private CachedClient(OssConfig config, OSS oss) {
            this.config = config;
            this.oss = oss;
        }
    }
}
