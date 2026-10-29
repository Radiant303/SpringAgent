package com.spring.gateway.storage.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 文件资源路径检查。
 *
 * @author hanbing
 * @since 2026-10-29
 */
public final class StoragePaths {
    private StoragePaths() {
    }

    /**
     * 读取普通文件或目录的属性，路径不存在时返回 null。
     *
     * @param target 目标路径
     * @return 文件属性，路径不存在时为 null
     */
    public static BasicFileAttributes checkedAttributes(Path target) {
        Path current = target.getRoot();
        BasicFileAttributes attributes = null;
        try {
            for (Path segment : target) {
                current = current.resolve(segment);
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()
                        || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))
                        || (!current.equals(target) && !attributes.isDirectory())
                        || (current.equals(target) && !attributes.isDirectory() && !attributes.isRegularFile())) {
                    throw new StorageException("文件资源须由普通目录和普通文件组成");
                }
            }
            return attributes;
        } catch (NoSuchFileException ignored) {
            return null;
        } catch (IOException | SecurityException e) {
            throw new StorageException("文件资源状态读取失败", e);
        }
    }
}
