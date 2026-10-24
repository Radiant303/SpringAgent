package com.spring.gateway.catalog.service;

import com.spring.gateway.catalog.entity.ModelProvider;

import java.util.List;

/**
 * Provider 模型目录客户端端口。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface ProviderModelsClient {
    List<String> listModels(ModelProvider provider);
}
