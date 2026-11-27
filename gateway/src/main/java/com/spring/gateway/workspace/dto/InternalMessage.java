package com.spring.gateway.workspace.dto;

import tools.jackson.databind.JsonNode;

/**
 * 一条历史消息：段号 + payload 原文（JSON 列内容，网关不解析其结构）。
 *
 * @author hanbing
 * @since 2026-10-03
 * @param segmentNo 段号
 * @param payload   消息 JSON 原文
 * @param id        消息主键
 * @param nodeId    回答变体节点 ID
 * @param legacyId  旧消息原始主键
 */
public record InternalMessage(Integer segmentNo, JsonNode payload, Long id, String nodeId, Long legacyId) {
    public InternalMessage(Integer segmentNo, JsonNode payload) {
        this(segmentNo, payload, null, null, null);
    }
}
