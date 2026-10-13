package com.spring.gateway.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 模型定义的新建/修改请求。capabilities/supportEfforts 以数组传入，服务端存 CSV。
 *
 * @author hanbing
 * @since 2026-10-09
 * @param originalId     编辑锚点：被编辑模型的原 ID；null/空 = 新建。与原 ID 不同时按改名处理
 * @param provider       所属 Provider（必须已存在）
 * @param model          实际模型名（API 请求的 model 字段）
 * @param displayName    展示名
 * @param maxContextSize 上下文窗口（tokens）
 * @param maxOutputSize  输出上限（tokens），0 = 不限
 * @param capabilities   能力列表
 * @param supportEfforts 支持的思考档位
 * @param defaultEffort  默认思考档位；空 = 不指定
 * @param enabled        是否启用
 */
public record AdminModelDefRequest(
        @Size(max = 256, message = "原模型 ID 长度不能超过 256")
        String originalId,

        @NotBlank(message = "Provider 不能为空")
        String provider,

        @NotBlank(message = "模型名不能为空")
        @Size(max = 128, message = "模型名长度不能超过 128")
        String model,

        @NotBlank(message = "展示名不能为空")
        @Size(max = 64, message = "展示名长度不能超过 64")
        String displayName,

        @NotNull(message = "上下文窗口不能为空")
        @Min(value = 1, message = "上下文窗口必须为正数")
        Long maxContextSize,

        @NotNull(message = "输出上限不能为空")
        @Min(value = 0, message = "输出上限不能为负")
        Long maxOutputSize,

        List<@NotNull(message = "不支持的模型能力")
                @Pattern(regexp = "thinking|image_in|tool_use", message = "不支持的模型能力")
                String> capabilities,
        List<String> supportEfforts,

        @Size(max = 16, message = "默认档位长度不能超过 16")
        String defaultEffort,

        @NotNull(message = "enabled 不能为空")
        Boolean enabled
) {
}
