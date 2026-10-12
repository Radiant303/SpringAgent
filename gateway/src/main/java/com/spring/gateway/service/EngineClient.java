package com.spring.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;

/**
 * 引擎 HTTP 客户端。
 *
 * @author hanbing
 * @since 2026-10-04
 */
@Component
public class EngineClient {

    private final RestClient restClient;

    public EngineClient(@Value("${app.engine-base-url}") String engineBaseUrl,
                        @Value("${app.internal-token}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(40));
        this.restClient = RestClient.builder()
                .baseUrl(engineBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .requestFactory(factory)
                .build();
    }

    /**
     * 查询引擎进程内某 work 的活跃会话数
     *
     * @param workId work ID
     * @return 活跃会话数
     */
    public int activeSessionsInWork(String workId) {
        JsonNode body = restClient.get()
                .uri("/internal/works/{workId}/active-sessions", workId)
                .retrieve()
                .body(JsonNode.class);
        return body == null ? 0 : body.get("active").asInt();
    }

    /**
     * 请求引擎真实调用一次指定模型
     *
     * @param modelId 模型 ID
     * @return 引擎返回的调用结果
     */
    public JsonNode testModel(String modelId) {
        return restClient.post()
                .uri("/internal/models/test")
                .body(Map.of("model_id", modelId))
                .retrieve()
                .body(JsonNode.class);
    }
}
