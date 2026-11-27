package com.spring.gateway.workspace.dto;

/**
 * 回答变体准备结果。
 *
 * @author hanbing
 * @since 2026-11-27
 * @param branchId 新节点 ID
 * @param input 实际发送给模型的用户输入
 */
public record InternalBranchResult(String branchId, String input) {
}
