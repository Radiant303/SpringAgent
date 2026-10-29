package com.spring.gateway.knowledge.service;

import com.spring.gateway.knowledge.dto.KnowledgeDocumentContent;
import com.spring.gateway.knowledge.dto.KnowledgeDocumentSummary;

import java.util.List;

/**
 * 个人知识库查询业务。
 *
 * @author hanbing
 * @since 2026-10-29
 */
public interface KnowledgeService {

    /**
     * 查询用户的知识文档元数据。
     *
     * @param userId 用户 ID
     * @return 按固定类型排序的元数据
     */
    List<KnowledgeDocumentSummary> list(long userId);

    /**
     * 打开用户指定类型的知识文档，调用方负责关闭输入流。
     *
     * @param userId 用户 ID
     * @param type 文档类型
     * @return 文档输入流与字节数
     */
    KnowledgeDocumentContent open(long userId, String type);
}
