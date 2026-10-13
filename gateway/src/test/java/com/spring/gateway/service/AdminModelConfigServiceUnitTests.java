package com.spring.gateway.service;

import com.spring.gateway.common.BizException;
import com.spring.gateway.dto.AdminModelTestResult;
import com.spring.gateway.entity.ModelDefinition;
import com.spring.gateway.entity.ModelProvider;
import com.spring.gateway.mapper.ModelDefinitionMapper;
import com.spring.gateway.mapper.ModelProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    private final ModelCatalogCache modelCatalogCache = mock(ModelCatalogCache.class);
    private final EngineClient engineClient = mock(EngineClient.class);
    private final ProviderModelsClient providerModelsClient = mock(ProviderModelsClient.class);

    private final AdminModelConfigService service = new AdminModelConfigService(
            providerMapper, modelMapper, systemSettingService, modelCatalogCache,
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
}
