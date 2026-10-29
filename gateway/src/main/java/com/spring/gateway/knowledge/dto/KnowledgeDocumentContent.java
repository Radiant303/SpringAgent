package com.spring.gateway.knowledge.dto;

import java.io.InputStream;

/**
 * 个人知识文档输入流与字节数。
 *
 * @author hanbing
 * @since 2026-10-29
 */
public record KnowledgeDocumentContent(InputStream inputStream, long sizeBytes) {
}
