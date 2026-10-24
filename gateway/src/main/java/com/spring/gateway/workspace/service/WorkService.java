package com.spring.gateway.workspace.service;

import com.spring.gateway.workspace.entity.Work;

import java.util.List;

/**
 * 项目业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface WorkService {
    String DEFAULT_WORK_NAME = "默认项目";

    List<Work> list(Long userId);

    Work create(Long userId, String name);

    Work ensureDefault(Long userId);

    Work getOwned(Long userId, String workId);

    Work getForInternal(String workId);

    void hardDelete(Long userId, String workId);

    String workspacePath(String workId);

    String knowledgePath(long userId);

    long effectiveWorkMaxBytes(Work row);

    StorageOverview storageOverview(long userId);

    record StorageOverview(long usedBytes, long quotaBytes, long workMaxBytes) {
    }
}
