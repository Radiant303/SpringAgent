package com.spring.gateway.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 官方社群链接请求。
 *
 * @author hanbing
 * @since 2026-10-11
 * @param link 社群链接，空串表示不展示入口
 */
public record AdminCommunityLinkRequest(
        @NotNull @Size(max = 512) String link
) {
}
