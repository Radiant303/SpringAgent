package com.spring.gateway.catalog.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.spring.gateway.common.BizException;
import com.spring.gateway.catalog.entity.ModelProvider;
import com.spring.gateway.catalog.service.ProviderModelsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Provider 模型列表 HTTP 测试。
 *
 * @author hanbing
 * @since 2026-10-23
 */
class ProviderModelsClientTests {

    private HttpServer server;
    private String baseUrl;
    private final ProviderModelsClient client = new HttpProviderModelsClient();
    private final List<Request> requests = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void anthropicUsesHeadersAndFollowsPagesWithDeduplicatedSortedModels() {
        server.createContext("/v1/models", exchange -> {
            recordRequest(exchange);
            if (requests.size() == 1) {
                respond(exchange, 200, """
                        {"data":[{"id":"z-model"},{"id":"shared"}],
                         "has_more":true,"last_id":"cursor+/=&?"}
                        """);
            } else {
                respond(exchange, 200, """
                        {"data":[{"id":"a-model"},{"id":"shared"},{"id":42},{}],
                         "has_more":false,"last_id":null}
                        """);
            }
        });

        assertEquals(List.of("a-model", "shared", "z-model"), client.listModels(provider("anthropic", baseUrl)));
        assertEquals(List.of("/v1/models", "/v1/models?after_id=cursor%2B%2F%3D%26%3F"),
                requests.stream().map(Request::uri).toList());
        for (Request request : requests) {
            assertEquals("GET", request.method());
            assertEquals("test-key", request.apiKey());
            assertEquals("2023-06-01", request.version());
            assertEquals(null, request.authorization());
        }
    }

    @Test
    void anthropicPreservesCustomPathIncludingUserSuppliedV1() {
        server.createContext("/proxy/v1/v1/models", exchange -> {
            recordRequest(exchange);
            respond(exchange, 200, "{\"data\":[],\"has_more\":false}");
        });

        assertEquals(List.of(), client.listModels(provider("anthropic", " " + baseUrl + "/proxy/v1/// ")));
        assertEquals("/proxy/v1/v1/models", requests.getFirst().uri());
    }

    @Test
    void anthropicRejectsMissingBlankAndNonTextCursors() {
        String[] response = new String[1];
        server.createContext("/v1/models", exchange -> {
            recordRequest(exchange);
            respond(exchange, 200, response[0]);
        });
        for (String cursor : List.of("", ",\"last_id\":null", ",\"last_id\":\"\"",
                ",\"last_id\":\"  \"", ",\"last_id\":42")) {
            response[0] = "{\"data\":[],\"has_more\":true" + cursor + "}";
            assertBadGateway("anthropic");
        }
        assertEquals(5, requests.size());
    }

    @Test
    void anthropicRejectsRepeatedCursor() {
        AtomicInteger count = new AtomicInteger();
        server.createContext("/v1/models", exchange -> respond(exchange, 200,
                count.incrementAndGet() <= 2
                        ? "{\"data\":[],\"has_more\":true,\"last_id\":\"cursor\"}"
                        : "{\"data\":[],\"has_more\":false}"));

        assertBadGateway("anthropic");
        assertEquals(2, count.get());
    }

    @Test
    void anthropicRejectsCursorCycles() {
        AtomicInteger count = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            int page = count.incrementAndGet();
            respond(exchange, 200, page <= 3
                    ? "{\"data\":[],\"has_more\":true,\"last_id\":\"cursor-" + (page % 2) + "\"}"
                    : "{\"data\":[],\"has_more\":false}");
        });

        assertBadGateway("anthropic");
        assertEquals(3, count.get());
    }

    @Test
    void anthropicRejectsInvalidResponseStructureAndJson() {
        String[] response = new String[1];
        server.createContext("/v1/models", exchange -> respond(exchange, 200, response[0]));
        for (String body : List.of("null", "{}", "{\"data\":{}}", "{\"data\":[]}",
                "{\"data\":[],\"has_more\":\"true\"}", "not-json")) {
            response[0] = body;
            assertBadGateway("anthropic");
        }
    }

    @Test
    void upstreamHttpErrorsRemainBadGatewayWithBriefDetail() {
        server.createContext("/", exchange -> respond(exchange, 401, "unauthorized ".repeat(100)));
        for (String type : List.of("anthropic", "openai")) {
            BizException error = assertBadGateway(type);
            assertEquals("拉取模型列表失败: ", error.getMessage().substring(0, 10));
            assertEquals(210, error.getMessage().length());
        }
    }

    @Test
    void anthropicDoesNotReturnPartialModelsAfterLaterPageError() {
        AtomicInteger count = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            if (count.incrementAndGet() == 1) {
                respond(exchange, 200,
                        "{\"data\":[{\"id\":\"model\"}],\"has_more\":true,\"last_id\":\"model\"}");
            } else {
                respond(exchange, 500, "upstream down");
            }
        });

        assertBadGateway("anthropic");
        assertEquals(2, count.get());
    }

    @Test
    void connectionErrorsRemainBadGateway() {
        server.stop(0);
        assertBadGateway("anthropic");
        assertBadGateway("openai");
    }

    @Test
    void compatibleProvidersRemainBearerSinglePageModelsRequests() {
        server.createContext("/compatible/v1/models", exchange -> {
            recordRequest(exchange);
            respond(exchange, 200, """
                    {"data":[{"id":"gpt-z"},{"id":"gpt-a"},{"id":"gpt-a"},{"id":42},{}],
                     "has_more":true}
                    """);
        });
        for (String type : List.of("openai", "responses", "deepseek", "alibaba")) {
            assertEquals(List.of("gpt-a", "gpt-z"),
                    client.listModels(provider(type, baseUrl + "/compatible/v1///")));
        }
        assertEquals(4, requests.size());
        for (Request request : requests) {
            assertEquals("/compatible/v1/models", request.uri());
            assertEquals("GET", request.method());
            assertEquals("Bearer test-key", request.authorization());
            assertEquals(null, request.apiKey());
            assertEquals(null, request.version());
        }
    }

    @Test
    void compatibleProvidersRejectInvalidData() {
        server.createContext("/models", exchange -> respond(exchange, 200, "{\"data\":{}}"));
        assertBadGateway("openai");
    }

    @Test
    void providerWithoutDefaultOrCustomAddressRemainsBadRequest() {
        BizException error = assertThrows(BizException.class,
                () -> client.listModels(provider("unknown", null)));
        assertEquals(400, error.getStatus());
    }

    private BizException assertBadGateway(String type) {
        BizException error = assertThrows(BizException.class, () -> client.listModels(provider(type, baseUrl)));
        assertEquals(502, error.getStatus());
        return error;
    }

    private void recordRequest(HttpExchange exchange) {
        var headers = exchange.getRequestHeaders();
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                headers.getFirst("Authorization"), headers.getFirst("x-api-key"),
                headers.getFirst("anthropic-version")));
    }

    private record Request(String method, String uri, String authorization, String apiKey, String version) {
    }

    private static ModelProvider provider(String type, String url) {
        ModelProvider provider = new ModelProvider();
        provider.setType(type);
        provider.setBaseUrl(url);
        provider.setApiKey("test-key");
        return provider;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
