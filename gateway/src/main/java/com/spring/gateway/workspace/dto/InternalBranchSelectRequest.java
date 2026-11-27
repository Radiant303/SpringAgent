package com.spring.gateway.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 回答变体切换请求。
 *
 * @author hanbing
 * @since 2026-11-27
 * @param userId 用户 ID
 * @param branchId 目标节点 ID
 */
public record InternalBranchSelectRequest(@NotNull Long userId, @NotBlank String branchId) {
}
