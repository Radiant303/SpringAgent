package com.spring.gateway.workspace.service;

import com.spring.gateway.workspace.entity.PageMapping;

/**
 * 页面映射业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface PageMappingService {
    PageMapping ensureForWork(String workId);

    PageMapping find(long pageId);

    Long pageIdOf(String workId);

    void deleteByWorkId(String workId);
}
