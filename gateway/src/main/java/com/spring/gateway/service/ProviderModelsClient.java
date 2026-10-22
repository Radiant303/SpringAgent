package com.spring.gateway.service;

import com.spring.gateway.common.BizException;
import com.spring.gateway.entity.ModelProvider;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Provider 上游接口客户端。
 *
 * @author hanbing
 * @since 2026-10-12
 */
@Component
public class ProviderModelsClient {

    /** 错误描述里上游响应体的截断长度（字符） */
    private static final int ERROR_DETAIL_MAX_CHARS = 200;

    private final RestClient restClient;

    public ProviderModelsClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * 拉取 Provider 上游支持的模型 ID 列表
     *
     * @param provider Provider
     * @return 去重升序的模型 ID 列表
     * @throws BizException 上游非 2xx、网络异常或响应结构不符（502）；Provider 无可用地址（400）
     */
    public List<String> listModels(ModelProvider provider) {
        String baseUrl = resolveBaseUrl(provider);
        return "anthropic".equals(provider.getType())
                ? listAnthropicModels(provider, baseUrl)
                : listCompatibleModels(provider, baseUrl);
    }

    private List<String> listCompatibleModels(ModelProvider provider, String baseUrl) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri(baseUrl + "/models")
                    .header("Authorization", "Bearer " + provider.getApiKey())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new BizException(502, "拉取模型列表失败: " + brief(e));
        }
        JsonNode data = body == null ? null : body.get("data");
        if (data == null || !data.isArray()) {
            throw new BizException(502, "拉取模型列表失败: 响应结构不符");
        }
        TreeSet<String> ids = new TreeSet<>();
        addIds(data, ids);
        return List.copyOf(ids);
    }

    private List<String> listAnthropicModels(ModelProvider provider, String baseUrl) {
        TreeSet<String> ids = new TreeSet<>();
        Set<String> cursors = new HashSet<>();
        String afterId = null;
        while (true) {
            String url = baseUrl + "/v1/models" + (afterId == null ? ""
                    : "?after_id=" + URLEncoder.encode(afterId, StandardCharsets.UTF_8));
            JsonNode body;
            try {
                body = restClient.get()
                        .uri(URI.create(url))
                        .header("x-api-key", provider.getApiKey())
                        .header("anthropic-version", "2023-06-01")
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientException e) {
                throw new BizException(502, "拉取模型列表失败: " + brief(e));
            }
            JsonNode data = body == null ? null : body.get("data");
            if (data == null || !data.isArray()) {
                throw new BizException(502, "拉取模型列表失败: 响应结构不符");
            }
            addIds(data, ids);
            JsonNode hasMore = body.get("has_more");
            if (hasMore == null || !hasMore.isBoolean()) {
                throw new BizException(502, "拉取模型列表失败: 响应结构不符");
            }
            if (!hasMore.booleanValue()) {
                return List.copyOf(ids);
            }
            JsonNode lastId = body.get("last_id");
            if (lastId == null || !lastId.isTextual() || lastId.textValue().isBlank()
                    || !cursors.add(lastId.textValue())) {
                throw new BizException(502, "拉取模型列表失败: 分页游标无效");
            }
            afterId = lastId.textValue();
        }
    }

    private static void addIds(JsonNode data, Set<String> ids) {
        for (JsonNode item : data) {
            JsonNode id = item.get("id");
            if (id != null && id.isTextual()) {
                ids.add(id.textValue());
            }
        }
    }

    /** base_url 为空时按类型兜底官方地址，并去掉末尾斜杠。 */
    private static String resolveBaseUrl(ModelProvider provider) {
        String baseUrl = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().trim();
        if (baseUrl.isEmpty()) {
            baseUrl = ModelProvider.DEFAULT_BASE_URLS.getOrDefault(provider.getType(), "");
        }
        if (baseUrl.isEmpty()) {
            throw new BizException(400, "Provider 未配置 base_url，且类型无默认地址: " + provider.getType());
        }
        return baseUrl.replaceAll("/+$", "");
    }

    /** 取异常简述；超长截断。 */
    private static String brief(RestClientException e) {
        String detail = e.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = e.getClass().getSimpleName();
        }
        return detail.length() > ERROR_DETAIL_MAX_CHARS ? detail.substring(0, ERROR_DETAIL_MAX_CHARS) : detail;
    }
}
