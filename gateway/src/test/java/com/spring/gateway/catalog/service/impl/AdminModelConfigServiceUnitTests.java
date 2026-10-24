package com.spring.gateway.catalog.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.catalog.dto.AdminModelTestResult;
import com.spring.gateway.catalog.dto.AdminProviderRequest;
import com.spring.gateway.catalog.entity.ModelDefinition;
import com.spring.gateway.catalog.entity.ModelProvider;
import com.spring.gateway.catalog.mapper.ModelDefinitionMapper;
import com.spring.gateway.catalog.mapper.ModelProviderMapper;
import com.spring.gateway.catalog.infrastructure.ModelCatalogCache;
import com.spring.gateway.catalog.service.AdminModelConfigService;
import com.spring.gateway.catalog.service.ProviderModelsClient;
import com.spring.gateway.engine.client.EngineClient;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.settings.service.SystemSettingWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型配置管理的单元测试：外部客户端以 Mockito mock。
 *
 * @author hanbing
 * @since 2026-10-12
 */
class AdminModelConfigServiceUnitTests {

    private final ModelProviderMapper providerMapper = mock(ModelProviderMapper.class);
    private final ModelDefinitionMapper modelMapper = mock(ModelDefinitionMapper.class);
    private final SystemSettingService systemSettingService = mock(SystemSettingService.class);
    private final SystemSettingWriter systemSettingWriter = mock(SystemSettingWriter.class);
    private final ModelCatalogCache modelCatalogCache = mock(ModelCatalogCache.class);
    private final EngineClient engineClient = mock(EngineClient.class);
    private final ProviderModelsClient providerModelsClient = mock(ProviderModelsClient.class);

    private final AdminModelConfigService service = new AdminModelConfigServiceImpl(
            providerMapper, modelMapper, systemSettingService, systemSettingWriter, modelCatalogCache,
            engineClient, providerModelsClient);

    @Test
    @DisplayName("listAvailableModels：Provider 不存在 404；存在时透传上游列表")
    void listAvailableModels() {
        BizException missing = assertThrows(BizException.class,
                () -> service.listAvailableModels("no-such"));
        assertEquals(404, missing.getStatus());

        when(providerMapper.selectById("p1")).thenReturn(new ModelProvider());
        when(providerModelsClient.listModels(any(ModelProvider.class)))
                .thenReturn(List.of("gpt-4o", "gpt-4o-mini"));
        assertEquals(List.of("gpt-4o", "gpt-4o-mini"), service.listAvailableModels("p1"));
    }

    @Test
    @DisplayName("testModel：模型不存在 404；引擎结果映射 ok/latency_ms/error；引擎不可达 502")
    void testModel() {
        BizException missing = assertThrows(BizException.class, () -> service.testModel("no/such"));
        assertEquals(404, missing.getStatus());

        when(modelMapper.selectById("p1/m1")).thenReturn(new ModelDefinition());

        var ok = JsonNodeFactory.instance.objectNode();
        ok.put("ok", true);
        ok.put("latency_ms", 42L);
        when(engineClient.testModel("p1/m1")).thenReturn(ok);
        AdminModelTestResult passed = service.testModel("p1/m1");
        assertTrue(passed.ok());
        assertEquals(42L, passed.latencyMs());
        assertNull(passed.error());

        // 字段缺失：ok 缺省 false，latency_ms / error 可空
        when(engineClient.testModel("p1/m1")).thenReturn(JsonNodeFactory.instance.objectNode());
        AdminModelTestResult empty = service.testModel("p1/m1");
        assertFalse(empty.ok());
        assertNull(empty.latencyMs());
        assertNull(empty.error());

        when(engineClient.testModel("p1/m1")).thenThrow(new RestClientException("engine down"));
        BizException unreachable = assertThrows(BizException.class, () -> service.testModel("p1/m1"));
        assertEquals(502, unreachable.getStatus());
    }

    @Test
    @DisplayName("upsertProvider：支持新建和更新 Anthropic Provider")
    void upsertAnthropicProvider() {
        when(providerMapper.selectById("anthropic-new")).thenReturn(null);
        service.upsertProvider(new AdminProviderRequest("anthropic-new", "anthropic", "key", null));
        ArgumentCaptor<ModelProvider> inserted = ArgumentCaptor.forClass(ModelProvider.class);
        verify(providerMapper).insert(inserted.capture());
        assertEquals("anthropic", inserted.getValue().getType());
        assertEquals("key", inserted.getValue().getApiKey());
        assertNull(inserted.getValue().getBaseUrl());

        ModelProvider existing = new ModelProvider();
        existing.setName("anthropic-existing");
        existing.setType("openai");
        existing.setApiKey("old-key");
        when(providerMapper.selectById("anthropic-existing")).thenReturn(existing);
        service.upsertProvider(new AdminProviderRequest(
                "anthropic-existing", "anthropic", "new-key", "https://example.com"));
        ArgumentCaptor<ModelProvider> updated = ArgumentCaptor.forClass(ModelProvider.class);
        verify(providerMapper).updateById(updated.capture());
        assertEquals("anthropic", updated.getValue().getType());
        assertEquals("new-key", updated.getValue().getApiKey());
        assertEquals("https://example.com", updated.getValue().getBaseUrl());
    }
}
