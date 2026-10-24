package com.spring.gateway.storage.infrastructure;

import com.spring.gateway.storage.service.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 受管上传文件的本地磁盘存储。
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
        Path works = root.resolve("works");
        try {
            checkedRealPath(works);
            Files.createDirectories(works);
            checkedRealPath(works);
            if (!Files.isDirectory(works, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(works)) {
                throw new IOException("工作区目录不可写");
            }
        } catch (IOException | SecurityException e) {
            throw new StorageException("本地存储工作区初始化失败: " + works, e);
        }
    }

    @Override
    public String upload(InputStream content, long size, String key, String mimeType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            target = resolve(key);
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
        Path works = root.resolve("works");
        try {
            checkedRealPath(works);
        } catch (IOException | SecurityException e) {
            throw new StorageException("本地存储目录不可用", e);
        }
        if (!Files.isDirectory(works, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<StoredObject> objects = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(works, 4)) {
            walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).forEach(path -> {
                String key = root.relativize(path).toString().replace('\\', '/');
                if (!StorageKeys.isManaged(key)) {
                    return;
                }
                try {
                    Path checked = resolve(key);
                    objects.add(new StoredObject(key,
                            Files.getLastModifiedTime(checked, LinkOption.NOFOLLOW_LINKS).toInstant()));
                } catch (IOException | StorageException e) {
                    log.warn("对账列举本地文件失败");
                }
            });
        } catch (IOException e) {
            throw new StorageException("本地存储列举失败", e);
        }
        return objects;
    }

    private Path resolve(String key) {
        if (key == null || key.contains("..") || key.indexOf('\\') >= 0 || key.indexOf(':') >= 0
                || !StorageKeys.isManaged(key)) {
            throw new StorageException("非法存储 key");
        }
        try {
            Path relative = Path.of(key);
            Path works = root.resolve("works");
            Path resolved = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !resolved.startsWith(works)
                    || !checkedRealPath(resolved).startsWith(checkedRealPath(works))) {
                throw new StorageException("非法存储路径");
            }
            return resolved;
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            throw new StorageException("本地存储路径不可用", e);
        }
    }

    private static Path checkedRealPath(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path segment : path) {
            current = current.resolve(segment);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                Path parent = current.getParent();
                return parent.toRealPath().resolve(parent.relativize(path));
            }
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("存储路径包含链接");
            }
        }
        return path.toRealPath();
    }
}
