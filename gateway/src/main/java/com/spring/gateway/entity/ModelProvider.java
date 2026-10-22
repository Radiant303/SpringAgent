package com.spring.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * model_providers 表的实体。
 *
 * @author hanbing
 * @since 2026-10-09
 */
@Data
@TableName("model_providers")
public class ModelProvider {

    /** 支持的 Provider 类型。 */
    public static final java.util.Set<String> SUPPORTED_TYPES =
            java.util.Set.of("openai", "alibaba", "deepseek", "responses", "anthropic");

    /** 类型未填 base_url 时使用的官方地址。 */
    public static final java.util.Map<String, String> DEFAULT_BASE_URLS = java.util.Map.of(
            "openai", "https://api.openai.com/v1",
            "alibaba", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "deepseek", "https://api.deepseek.com",
            "responses", "https://api.openai.com/v1",
            "anthropic", "https://api.anthropic.com");

    /** Provider 名，主键（模型定义经 provider 列引用）。 */
    @TableId(type = IdType.INPUT)
    private String name;

    /** 类型：openai / alibaba / deepseek / responses / anthropic。 */
    private String type;

    /** API Key。 */
    private String apiKey;

    /** 自定义 base_url；为空时使用官方地址。 */
    private String baseUrl;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
