package com.spring.gateway.knowledge.dto;

/**
 * 个人知识文档元数据。
 *
 * @author hanbing
 * @since 2026-10-29
 */
public record KnowledgeDocumentSummary(String type, boolean exists, long sizeBytes, String updatedAt) {
}
