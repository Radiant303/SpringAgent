package com.spring.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 管理后台的模型连通性测试结果。
 *
 * @author hanbing
 * @since 2026-10-12
 * @param ok        是否调通
 * @param latencyMs 调用耗时（毫秒）
 * @param error     失败原因；成功时为空
 */
public record AdminModelTestResult(
        boolean ok,
        @JsonProperty("latency_ms") Long latencyMs,
        String error
) {
}
