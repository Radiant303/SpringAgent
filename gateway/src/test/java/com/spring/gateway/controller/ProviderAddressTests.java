package com.spring.gateway.controller;

import com.spring.gateway.dto.AdminProviderView;
import com.spring.gateway.entity.ModelProvider;
import com.spring.gateway.service.AdminModelConfigService;
import com.spring.gateway.service.SystemSettingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 提供商地址展示测试。
 *
 * @author hanbing
 * @since 2026-10-13
 */
class ProviderAddressTests {

    @Test
    void exposesDomesticAlibabaDefaultAndCustomAddresses() {
        AdminModelConfigService service = mock(AdminModelConfigService.class);
        SystemSettingService settings = mock(SystemSettingService.class);
        ModelProvider alibaba = provider("alibaba", "alibaba", null);
        ModelProvider custom = provider("custom", "openai", "https://example.com/v1");
        when(service.listProviders()).thenReturn(List.of(alibaba, custom));
        when(service.listModels()).thenReturn(List.of());
        when(service.getSubagentModels()).thenReturn(Map.of());
        when(settings.getDefaultModel()).thenReturn("");
        var response = new AdminModelConfigController(service, settings).get();
        assertEquals(200, response.getCode());
        assertEquals(ModelProvider.DEFAULT_BASE_URLS, response.getData().get("providerDefaults"));
        List<?> providers = (List<?>) response.getData().get("providers");
        AdminProviderView defaultView = (AdminProviderView) providers.getFirst();
        assertNull(defaultView.baseUrl());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", defaultView.effectiveBaseUrl());
        AdminProviderView customView = (AdminProviderView) providers.get(1);
        assertEquals("https://example.com/v1", customView.baseUrl());
        assertEquals(customView.baseUrl(), customView.effectiveBaseUrl());
    }

    @Test
    void exposesAnthropicDefaultRootAndUnchangedCustomAddress() {
        AdminModelConfigService service = mock(AdminModelConfigService.class);
        SystemSettingService settings = mock(SystemSettingService.class);
        ModelProvider anthropic = provider("anthropic", "anthropic", null);
        ModelProvider custom = provider("custom", "anthropic", "https://example.com/proxy/v1");
        when(service.listProviders()).thenReturn(List.of(anthropic, custom));
        when(service.listModels()).thenReturn(List.of());
        when(service.getSubagentModels()).thenReturn(Map.of());
        when(settings.getDefaultModel()).thenReturn("");

        var response = new AdminModelConfigController(service, settings).get();

        assertEquals(200, response.getCode());
        Map<?, ?> defaults = (Map<?, ?>) response.getData().get("providerDefaults");
        assertEquals("https://api.anthropic.com", defaults.get("anthropic"));
        List<?> providers = (List<?>) response.getData().get("providers");
        AdminProviderView defaultView = (AdminProviderView) providers.getFirst();
        assertNull(defaultView.baseUrl());
        assertEquals("https://api.anthropic.com", defaultView.effectiveBaseUrl());
        AdminProviderView customView = (AdminProviderView) providers.get(1);
        assertEquals("https://example.com/proxy/v1", customView.baseUrl());
        assertEquals(customView.baseUrl(), customView.effectiveBaseUrl());
    }

    private ModelProvider provider(String name, String type, String baseUrl) {
        ModelProvider provider = new ModelProvider();
        provider.setName(name);
        provider.setType(type);
        provider.setBaseUrl(baseUrl);
        provider.setApiKey("test-key");
        return provider;
    }
}
