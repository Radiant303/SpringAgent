package com.spring.gateway.catalog.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 子代理模型配置请求：子代理名 → 模型 ID，空串表示继承主模型。
 *
 * @author hanbing
 * @since 2026-10-11
 * @param models 子代理名 → 模型 ID
 */
public record AdminSubagentModelsRequest(
        @NotEmpty @Size(max = 16) Map<String, String> models
) {
}
