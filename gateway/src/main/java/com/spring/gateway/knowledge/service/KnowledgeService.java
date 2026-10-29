package com.spring.gateway.knowledge.service;

import com.spring.gateway.knowledge.dto.KnowledgeDocumentView;

import java.util.List;

/**
 * 个人知识库查询业务。
 *
 * @author hanbing
 * @since 2026-10-29
 */
public interface KnowledgeService {

    /**
     * 查询用户的全部知识文档。
     *
     * @param userId 用户 ID
     * @return 按固定类型排序的知识文档
     */
    List<KnowledgeDocumentView> list(long userId);
}
