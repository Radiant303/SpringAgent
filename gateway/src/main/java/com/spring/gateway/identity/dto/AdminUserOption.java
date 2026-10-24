package com.spring.gateway.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用户筛选下拉的选项行（全量、轻量）。
 *
 * @author hanbing
 * @since 2026-10-11
 * @param userId   用户 ID（字符串：雪花 ID 超 2^53，JS 数字会丢精度）
 * @param username 用户名
 */
public record AdminUserOption(
        @JsonProperty("user_id") String userId,
        String username
) {
}
