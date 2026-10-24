package com.spring.gateway.catalog.service;

import com.spring.gateway.catalog.dto.AdminModelDefRequest;
import com.spring.gateway.catalog.dto.AdminModelTestResult;
import com.spring.gateway.catalog.dto.AdminProviderRequest;
import com.spring.gateway.catalog.entity.ModelDefinition;
import com.spring.gateway.catalog.entity.ModelProvider;

import java.util.List;
import java.util.Map;

/**
 * 模型配置管理业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface AdminModelConfigService {
    List<ModelProvider> listProviders();

    List<ModelDefinition> listModels();

    void upsertProvider(AdminProviderRequest req);

    void deleteProvider(String name);

    void upsertModel(AdminModelDefRequest req);

    void deleteModel(String id);

    void setDefaultModel(String modelId);

    Map<String, String> getSubagentModels();

    void setSubagentModels(Map<String, String> models);

    List<String> listAvailableModels(String providerName);

    AdminModelTestResult testModel(String modelId);
}
