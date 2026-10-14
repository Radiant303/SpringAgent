package com.spring.gateway.service.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地磁盘存储：key = 相对 data-root 的路径，落盘 = data-root + key。
 * 访问经 Nginx X-Accel-Redirect 分发（{@link #directUrl} 恒为空）。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@Service(StorageTypes.LOCAL)
public class LocalStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path root;

    public LocalStorageService(@Value("${app.data-root}") String dataRoot) {
        this.root = Path.of(dataRoot).toAbsolutePath().normalize();
    }

    @Override
    public String upload(InputStream content, long size, String key, String mimeType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("本地存储写入失败: " + e.getMessage(), e);
        }
        return key;
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("本地文件删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> directUrl(String key) {
        return Optional.empty();
    }

    @Override
    public Optional<Path> localPath(String key) {
        return Optional.of(resolve(key));
    }

    @Override
    public List<StoredObject> listObjects() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<StoredObject> objects = new ArrayList<>();
        // 只扫受管上传文件（works/{workId}/{sessionId}/images/{uuid}.{ext}，深度 5），
        // 工作区里的代理产物不属于上传文件，绝不能进对账的孤儿名单
        try (Stream<Path> walk = Files.walk(root, 5)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String key = root.relativize(path).toString().replace('\\', '/');
                if (!StorageKeys.isManaged(key)) {
                    return;
                }
                try {
                    objects.add(new StoredObject(key, Files.getLastModifiedTime(path).toInstant()));
                } catch (IOException e) {
                    log.warn("对账列举本地文件失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            throw new StorageException("本地存储列举失败: " + e.getMessage(), e);
        }
        return objects;
    }

    /** key 归一化后必须仍落在 root 内（防 ../ 穿越） */
    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (key.contains("..") || !resolved.startsWith(root)) {
            throw new StorageException("非法存储 key: " + key);
        }
        return resolved;
    }
}
