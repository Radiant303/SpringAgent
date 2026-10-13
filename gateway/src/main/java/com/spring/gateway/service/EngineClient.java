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
     * 获取连接持有的会话控制令牌。
     *
     * @param sessionId 会话 ID
     * @param userId 用户 ID
     * @param connectionId 连接 ID
     * @return 控制令牌；无控制权时为 null
     */
    public String sessionControlToken(String sessionId, long userId, String connectionId) {
        JsonNode result = restClient.post()
                .uri("/internal/sessions/{sessionId}/control", sessionId)
                .body(Map.of("user_id", userId, "connection_id", connectionId))
                .retrieve().body(JsonNode.class);
        if (result == null || !result.path("authorized").asBoolean(false)) {
            return null;
        }
        JsonNode token = result.get("controlToken");
        return token == null || !token.isTextual() || token.asText().isBlank() ? null : token.asText();
    }

    public JsonNode testModel(String modelId) {
        return restClient.post()
                .uri("/internal/models/test")
                .body(Map.of("model_id", modelId))
                .retrieve()
                .body(JsonNode.class);
    }
}
