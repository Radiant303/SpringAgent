package com.spring.gateway.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 回答变体准备请求。
 *
 * @author hanbing
 * @since 2026-11-27
 * @param userId 用户 ID
 * @param action 操作类型：normal / edit / retry
 * @param branchId 目标节点 ID
 * @param input 用户输入
 */
public record InternalBranchPrepareRequest(
        @NotNull Long userId,
        @NotBlank @Pattern(regexp = "normal|edit|retry") String action,
        String branchId,
        String input
) {
}
