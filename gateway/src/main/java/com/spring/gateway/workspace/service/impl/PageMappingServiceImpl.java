package com.spring.gateway.workspace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.workspace.entity.PageMapping;
import com.spring.gateway.workspace.mapper.PageMappingMapper;
import com.spring.gateway.workspace.service.PageMappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;

/**
 * 页面映射。
 *
 * @author hanbing
 * @since 2026-10-16
 */
@Service
@RequiredArgsConstructor
public class PageMappingServiceImpl implements PageMappingService {

    private final PageMappingMapper pageMappingMapper;
    private final SnowflakeIdGenerator idGenerator;

    @Value("${app.data-root}")
    private String dataRoot;

    /**
     * 确保 work 存在页面映射与 page/ 目录，缺则建。
     *
     * @param workId work ID
     * @return 页面映射行
     * @throws BizException 目录创建失败（500）
     */
    public PageMapping ensureForWork(String workId) {
        PageMapping existing = pageMappingMapper.selectOne(new LambdaQueryWrapper<PageMapping>()
                .eq(PageMapping::getWorkId, workId));
        if (existing != null) {
            return existing;
        }
        Path workDir = Path.of(dataRoot, "works", workId);
        Path pageDir = workDir.resolve("page");
        boolean workDirExisted = Files.isDirectory(workDir);
        try {
            Files.createDirectories(pageDir);
        } catch (IOException e) {
            throw new BizException(500, "页面目录创建失败");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_ROLLED_BACK) {
                        return;
                    }
                    deleteRecursively(pageDir);
                    if (!workDirExisted) {
                        try {
                            Files.deleteIfExists(workDir);
                        } catch (IOException ignored) {
                            // 非空说明回滚期间已有新内容写入，保留
                        }
                    }
                }
            });
        }
        PageMapping row = new PageMapping();
        row.setId(idGenerator.nextId());
        row.setWorkId(workId);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        pageMappingMapper.insert(row);
        return row;
    }

    /**
     * 按页面 ID 查映射。
     *
     * @param pageId 页面访问 ID
     * @return 映射行，不存在为 null
     */
    public PageMapping find(long pageId) {
        return pageMappingMapper.selectById(pageId);
    }

    /**
     * 按 work 查页面 ID。
     *
     * @param workId work ID
     * @return 页面访问 ID，无映射为 null
     */
    public Long pageIdOf(String workId) {
        PageMapping row = pageMappingMapper.selectOne(new LambdaQueryWrapper<PageMapping>()
                .eq(PageMapping::getWorkId, workId).select(PageMapping::getId));
        return row == null ? null : row.getId();
    }

    /**
     * 删除 work 的页面映射。
     *
     * @param workId work ID
     */
    public void deleteByWorkId(String workId) {
        pageMappingMapper.delete(new LambdaQueryWrapper<PageMapping>()
                .eq(PageMapping::getWorkId, workId));
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted((a, b) -> -a.compareTo(b)).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // 尽力删，残留不阻断回滚
                }
            });
        } catch (IOException ignored) {
            // 目录不可读视为已删除
        }
    }
}
