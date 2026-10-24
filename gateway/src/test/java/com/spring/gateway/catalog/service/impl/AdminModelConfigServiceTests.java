package com.spring.gateway.catalog.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.catalog.dto.AdminModelDefRequest;
import com.spring.gateway.catalog.dto.AdminProviderRequest;
import com.spring.gateway.catalog.entity.ModelDefinition;
import com.spring.gateway.catalog.entity.ModelProvider;
import com.spring.gateway.catalog.mapper.ModelDefinitionMapper;
import com.spring.gateway.catalog.mapper.ModelProviderMapper;
import com.spring.gateway.catalog.service.AdminModelConfigService;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.settings.service.SystemSettingWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 模型配置管理集成测试。连接真实 MySQL，@Transactional 回滚避免污染数据。
 *
 * @author hanbing
 * @since 2026-10-09
 */
@SpringBootTest
@Transactional
class AdminModelConfigServiceTests {

    @Autowired
    private AdminModelConfigService modelConfigService;
    @Autowired
    private ModelProviderMapper providerMapper;
    @Autowired
    private ModelDefinitionMapper modelMapper;
    @Autowired
    private SystemSettingService systemSettingService;

    /** 生成随机 Provider 名，避免用例间冲突。 */
    private static String randomName() {
        return "t" + UUID.randomUUID().toString().substring(0, 8);
    }

    private AdminProviderRequest providerReq(String name) {
        return new AdminProviderRequest(name, "openai", "sk-test", "https://api.example.com");
    }

    /** 模型 ID 由服务端派生 = provider + "/" + model，测试只需给 provider 与模型名。 */
    private AdminModelDefRequest modelReq(String originalId, String provider, String model) {
        return new AdminModelDefRequest(originalId, provider, model, "展示名",
                1_000_000L, 384_000L, List.of("thinking", "tool_use"), List.of("low", "high"),
                "low", true);
    }

    @Test
    @DisplayName("Provider upsert：新建必须带 Key；更新时 Key 留空保持不变；类型非法 400")
    void providerUpsert() {
        String name = randomName();

        BizException noKey = assertThrows(BizException.class,
                () -> modelConfigService.upsertProvider(new AdminProviderRequest(name, "openai", "", null)));
        assertEquals(400, noKey.getStatus());

        BizException badType = assertThrows(BizException.class,
                () -> modelConfigService.upsertProvider(new AdminProviderRequest(name, "unknown", "sk-x", null)));
        assertEquals(400, badType.getStatus());

        modelConfigService.upsertProvider(providerReq(name));
        ModelProvider created = providerMapper.selectById(name);
        assertNotNull(created);
        assertEquals("sk-test", created.getApiKey());

        // apiKey 留空 = 保持不变；baseUrl 同理
        modelConfigService.upsertProvider(new AdminProviderRequest(name, "deepseek", "", ""));
        ModelProvider updated = providerMapper.selectById(name);
        assertEquals("sk-test", updated.getApiKey());
        assertEquals("deepseek", updated.getType());
        assertEquals("https://api.example.com", updated.getBaseUrl());
    }

    @Test
    @DisplayName("Provider 删除：被模型引用时 409，删掉模型后可删")
    void providerDeleteGuard() {
        String name = randomName();
        modelConfigService.upsertProvider(providerReq(name));
        modelConfigService.upsertModel(modelReq(null, name, "m1"));

        BizException inUse = assertThrows(BizException.class, () -> modelConfigService.deleteProvider(name));
        assertEquals(409, inUse.getStatus());

        modelConfigService.deleteModel(name + "/m1");
        modelConfigService.deleteProvider(name);
        assertNull(providerMapper.selectById(name));
    }

    @Test
    @DisplayName("模型 upsert：Provider 必须存在；ID 派生为 provider/模型名；CSV 字段正确落库")
    void modelUpsert() {
        String name = randomName();

        BizException noProvider = assertThrows(BizException.class,
                () -> modelConfigService.upsertModel(modelReq(null, name, "m1")));
        assertEquals(400, noProvider.getStatus());

        modelConfigService.upsertProvider(providerReq(name));
        modelConfigService.upsertModel(modelReq(null, name, "m1"));
        ModelDefinition row = modelMapper.selectById(name + "/m1");
        assertNotNull(row);
        assertEquals("m1", row.getModel());
        assertEquals("thinking,tool_use", row.getCapabilities());
        assertEquals("low,high", row.getSupportEfforts());
    }

    @Test
    @DisplayName("模型改名：originalId 与派生 ID 不同则删旧插新；默认模型引用随之更新；目标已存在 409")
    void modelRename() {
        String name = randomName();
        modelConfigService.upsertProvider(providerReq(name));
        modelConfigService.upsertModel(modelReq(null, name, "m1"));
        modelConfigService.upsertModel(modelReq(null, name, "m2"));
        modelConfigService.setDefaultModel(name + "/m1");

        // 改名撞车：m1 → m2 已存在
        BizException clash = assertThrows(BizException.class,
                () -> modelConfigService.upsertModel(modelReq(name + "/m1", name, "m2")));
        assertEquals(409, clash.getStatus());

        // 改名：m1 → m3，默认模型引用跟随
        modelConfigService.upsertModel(modelReq(name + "/m1", name, "m3"));
        assertNull(modelMapper.selectById(name + "/m1"));
        assertNotNull(modelMapper.selectById(name + "/m3"));
        assertEquals(name + "/m3", systemSettingService.getDefaultModel());

        // originalId 不存在 → 404
        BizException missing = assertThrows(BizException.class,
                () -> modelConfigService.upsertModel(modelReq(name + "/nope", name, "m4")));
        assertEquals(404, missing.getStatus());
    }

    @Test
    @DisplayName("默认模型：设置/停用/删除保护")
    void defaultModelGuards() {
        String name = randomName();
        modelConfigService.upsertProvider(providerReq(name));
        modelConfigService.upsertModel(modelReq(null, name, "m1"));
        modelConfigService.upsertModel(modelReq(null, name, "m2"));

        BizException missing = assertThrows(BizException.class,
                () -> modelConfigService.setDefaultModel(name + "/nope"));
        assertEquals(400, missing.getStatus());

        modelConfigService.setDefaultModel(name + "/m1");
        assertEquals(name + "/m1", systemSettingService.getDefaultModel());

        // 默认模型不可停用、不可删除
        BizException disable = assertThrows(BizException.class,
                () -> modelConfigService.upsertModel(new AdminModelDefRequest(null, name,
                        "m1", "展示名", 1_000_000L, 0L, List.of(), List.of(), "", false)));
        assertEquals(409, disable.getStatus());
        BizException delete = assertThrows(BizException.class,
                () -> modelConfigService.deleteModel(name + "/m1"));
        assertEquals(409, delete.getStatus());

        // 切走默认后可删
        modelConfigService.setDefaultModel(name + "/m2");
        modelConfigService.deleteModel(name + "/m1");
        assertNull(modelMapper.selectById(name + "/m1"));
    }

    @Test
    @DisplayName("思考挡位：只允许标准挡位子集；默认挡位必须已选；关闭思考清空配置")
    void defaultEffortValidation() {
        String name = randomName();
        modelConfigService.upsertProvider(providerReq(name));
        for (String effort : java.util.Arrays.asList("max", " high", "HIGH", "", null, "low")) {
            BizException error = assertThrows(BizException.class, () -> modelConfigService.upsertModel(
                    new AdminModelDefRequest(null, name, "m1", "展示名", 1000L, 0L,
                            List.of("thinking"), List.of("high"), effort, true)));
            assertEquals(400, error.getStatus());
        }
        for (List<String> levels : List.of(List.<String>of(), List.of("high", "high"), List.of("max"))) {
            BizException error = assertThrows(BizException.class, () -> modelConfigService.upsertModel(
                    new AdminModelDefRequest(null, name, "m1", "展示名", 1000L, 0L,
                            List.of("thinking"), levels, "high", true)));
            assertEquals(400, error.getStatus());
        }
        for (String level : com.spring.gateway.common.ThinkingEffort.VALUES) {
            modelConfigService.upsertModel(new AdminModelDefRequest(null, name, "m1", "展示名", 1000L, 0L,
                    List.of("thinking"), List.of(level), level, true));
            ModelDefinition row = modelMapper.selectById(name + "/m1");
            assertEquals(level, row.getDefaultEffort());
            assertEquals(level, row.getSupportEfforts());
        }
        modelConfigService.upsertModel(new AdminModelDefRequest(null, name, "m1", "展示名", 1000L, 0L,
                List.of(), List.of("on"), "on", true));
        assertEquals("", modelMapper.selectById(name + "/m1").getDefaultEffort());
        assertEquals("", modelMapper.selectById(name + "/m1").getSupportEfforts());
    }

    @Test
    @DisplayName("listAvailableModels：Provider 不存在 404")
    void listAvailableModels() {
        BizException missing = assertThrows(BizException.class,
                () -> modelConfigService.listAvailableModels(randomName()));
        assertEquals(404, missing.getStatus());
    }

    @Test
    @DisplayName("testModel：模型不存在 404")
    void testModel() {
        BizException missing = assertThrows(BizException.class,
                () -> modelConfigService.testModel(randomName() + "/m1"));
        assertEquals(404, missing.getStatus());
    }
}
