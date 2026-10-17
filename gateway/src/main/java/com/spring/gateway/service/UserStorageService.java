package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.entity.FileRecord;
import com.spring.gateway.entity.Session;
import com.spring.gateway.entity.User;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.FileRecordMapper;
import com.spring.gateway.mapper.SessionMapper;
import com.spring.gateway.mapper.WorkMapper;
import com.spring.gateway.service.storage.StorageException;
import com.spring.gateway.service.storage.StorageTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户文件工作区的存储计量。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Service
@RequiredArgsConstructor
public class UserStorageService {
    private final WorkMapper workMapper;
    private final SessionMapper sessionMapper;
    private final FileRecordMapper fileRecordMapper;

    @Value("${app.data-root}")
    private String dataRoot;

    public Snapshot snapshot(User user) {
        List<Work> works = workMapper.selectList(new LambdaQueryWrapper<Work>().eq(Work::getUserId, user.getId()));
        Map<String, WorkUsage> usage = new LinkedHashMap<>();
        long workTotal = 0L;
        for (Work work : works) {
            long local = directoryBytes(work.getId());
            List<String> sessionIds = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                            .eq(Session::getWorkId, work.getId()).select(Session::getId))
                    .stream().map(Session::getId).toList();
            long remote = 0L;
            if (!sessionIds.isEmpty()) {
                remote = fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                                .eq(FileRecord::getUserId, user.getId()).in(FileRecord::getSessionId, sessionIds)
                                .ne(FileRecord::getStorageType, StorageTypes.LOCAL).select(FileRecord::getSizeBytes))
                        .stream().mapToLong(FileRecord::getSizeBytes).sum();
            }
            long total = Math.addExact(local, remote);
            usage.put(work.getId(), new WorkUsage(local, total));
            workTotal = Math.addExact(workTotal, total);
        }
        long uploads = user.getUsedBytes() == null ? 0L : user.getUsedBytes();
        return new Snapshot(Math.addExact(Math.max(uploads, workTotal), knowledgeBytes(user.getId())), Map.copyOf(usage));
    }

    public Path knowledgeRoot(long userId) {
        if (userId <= 0) {
            throw new StorageException("用户身份无效");
        }
        return Path.of(dataRoot).toAbsolutePath().normalize()
                .resolve("users").resolve(Long.toString(userId)).resolve("knowledge");
    }

    public long knowledgeBytes(long userId) {
        Path root = knowledgeRoot(userId);
        long total = 0L;
        for (String name : List.of("declarative.md", "procedural.md", "conditional.md")) {
            BasicFileAttributes attributes = checkedAttributes(root.resolve(name));
            if (attributes != null) {
                if (!attributes.isRegularFile()) {
                    throw new StorageException("知识文档需要为普通文件");
                }
                total = Math.addExact(total, attributes.size());
            }
        }
        return total;
    }

    public long directoryBytes(String workId) {
        Path root = Path.of(dataRoot, "works", workId).toAbsolutePath().normalize();
        BasicFileAttributes attributes = checkedAttributes(root);
        if (attributes == null) {
            return 0L;
        }
        if (!attributes.isDirectory()) {
            throw new StorageException("工作区需要为普通目录");
        }
        return treeBytes(root);
    }

    private static long treeBytes(Path root) {
        long total = 0L;
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                BasicFileAttributes attributes = checkedAttributes(path);
                if (attributes != null) {
                    total = Math.addExact(total, attributes.isDirectory() ? treeBytes(path) : attributes.size());
                }
            }
            return total;
        } catch (IOException | SecurityException e) {
            throw new StorageException("工作区大小读取失败", e);
        }
    }

    private static BasicFileAttributes checkedAttributes(Path target) {
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

    /**
     * 项目工作区的存储占用。
     *
     * @author hanbing
     * @since 2026-10-17
     */
    public record WorkUsage(long localBytes, long totalBytes) {
    }

    /**
     * 用户文件资源的存储快照。
     *
     * @author hanbing
     * @since 2026-10-17
     */
    public record Snapshot(long usedBytes, Map<String, WorkUsage> works) {
        public long workBytes(String workId) {
            WorkUsage usage = works.get(workId);
            return usage == null ? 0L : usage.totalBytes();
        }
    }
}
