package com.spring.gateway.controller;

import com.spring.gateway.common.RequiresOwner;
import com.spring.gateway.common.Result;
import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.dto.AdminDefaultModelRequest;
import com.spring.gateway.dto.AdminModelDefRequest;
import com.spring.gateway.dto.AdminModelDefView;
import com.spring.gateway.dto.AdminModelIdRequest;
import com.spring.gateway.dto.AdminModelTestResult;
import com.spring.gateway.dto.AdminProviderRequest;
import com.spring.gateway.dto.AdminProviderView;
import com.spring.gateway.dto.AdminSubagentModelsRequest;
import com.spring.gateway.entity.ModelDefinition;
import com.spring.gateway.entity.ModelProvider;
import com.spring.gateway.service.AdminModelConfigService;
import com.spring.gateway.service.SystemSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 模型配置管理接口：查看对 owner/admin 开放，修改仅站长。
 *
 * @author hanbing
 * @since 2026-10-09
 */
@RestController
@RequestMapping("/api/admin/model-config")
@RequiredArgsConstructor
public class AdminModelConfigController {

    private final AdminModelConfigService modelConfigService;
    private final SystemSettingService systemSettingService;

    /**
     * 模型配置快照：providers（不含 api_key）+ models + defaultModel + 子代理模型
     *
     * @return 配置快照
     */
    @GetMapping
    public Result<Map<String, Object>> get() {
        String defaultModel = systemSettingService.getDefaultModel();
        return Result.ok(Map.of(
                "providers", modelConfigService.listProviders().stream()
                        .map(AdminModelConfigController::toProviderView).toList(),
                "models", modelConfigService.listModels().stream()
                        .map(m -> toModelView(m, defaultModel)).toList(),
                "defaultModel", defaultModel,
                "providerDefaults", ModelProvider.DEFAULT_BASE_URLS,
                "subagentModels", modelConfigService.getSubagentModels()));
    }

    /**
     * 新建或更新 Provider；apiKey/baseUrl 留空 = 保持不变
     *
     * @param req Provider 请求
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可管理模型配置")
    @PostMapping("/providers")
    public Result<Void> upsertProvider(@Valid @RequestBody AdminProviderRequest req) {
        modelConfigService.upsertProvider(req);
        return Result.ok(null);
    }

    /**
     * 删除 Provider；被模型引用时拒绝
     *
     * @param name Provider 名
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可管理模型配置")
    @PostMapping("/providers/{name}/delete")
    public Result<Void> deleteProvider(@PathVariable String name) {
        modelConfigService.deleteProvider(name);
        return Result.ok(null);
    }

    /**
     * 拉取 Provider 上游支持的模型列表
     *
     * @param name Provider 名
     * @return 模型 ID 列表
     */
    @RequiresOwner(message = "仅站长可管理模型配置")
    @GetMapping("/providers/{name}/available-models")
    public Result<Map<String, Object>> availableModels(@PathVariable String name) {
        return Result.ok(Map.of("models", modelConfigService.listAvailableModels(name)));
    }

    /**
     * 新建或更新模型定义
     *
     * @param req 模型请求
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可管理模型配置")
    @PostMapping("/models")
    public Result<Void> upsertModel(@Valid @RequestBody AdminModelDefRequest req) {
        modelConfigService.upsertModel(req);
        return Result.ok(null);
    }

    /**
     * 删除模型定义；默认模型不可删。
     * id 含斜杠（provider/模型名），放请求体而不是路径变量（%2F 会被按路径分隔符处理）。
     *
     * @param req 模型 ID
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可管理模型配置")
    @PostMapping("/models/delete")
    public Result<Void> deleteModel(@Valid @RequestBody AdminModelIdRequest req) {
        modelConfigService.deleteModel(req.modelId());
        return Result.ok(null);
    }

    /**
     * 测试指定模型能否调通
     *
     * @param req 模型 ID
     * @return 测试结果
     */
    @RequiresOwner(message = "仅站长可管理模型配置")
    @PostMapping("/models/test")
    public Result<AdminModelTestResult> testModel(@Valid @RequestBody AdminModelIdRequest req) {
        return Result.ok(modelConfigService.testModel(req.modelId()));
    }

    /**
     * 设置默认模型
     *
     * @param req 模型 ID
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可管理模型配置")
    @PostMapping("/default")
    public Result<Void> setDefault(@Valid @RequestBody AdminDefaultModelRequest req) {
        modelConfigService.setDefaultModel(req.modelId());
        return Result.ok(null);
    }

    /**
     * 设置子代理模型；某个子代理传空串 = 继承主模型
     *
     * @param req 子代理名 → 模型 ID
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可管理模型配置")
    @PostMapping("/subagent-models")
    public Result<Void> setSubagentModels(@Valid @RequestBody AdminSubagentModelsRequest req) {
        modelConfigService.setSubagentModels(req.models());
        return Result.ok(null);
    }

    private static AdminProviderView toProviderView(ModelProvider row) {
        String effectiveBaseUrl = row.getBaseUrl() == null || row.getBaseUrl().isBlank()
                ? ModelProvider.DEFAULT_BASE_URLS.getOrDefault(row.getType(), "")
                : row.getBaseUrl();
        return new AdminProviderView(
                row.getName(),
                row.getType(),
                row.getBaseUrl(),
                effectiveBaseUrl,
                row.getApiKey() != null && !row.getApiKey().isBlank(),
                TimeFormat.isoUtc(row.getUpdatedAt()));
    }

    private static AdminModelDefView toModelView(ModelDefinition row, String defaultModel) {
        return new AdminModelDefView(
                row.getId(),
                row.getProvider(),
                row.getModel(),
                row.getDisplayName(),
                row.getMaxContextSize(),
                row.getMaxOutputSize(),
                splitCsv(row.getCapabilities()),
                splitCsv(row.getSupportEfforts()),
                row.getDefaultEffort(),
                row.getEnabled(),
                row.getId().equals(defaultModel),
                TimeFormat.isoUtc(row.getUpdatedAt()));
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
