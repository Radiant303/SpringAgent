package com.spring.gateway.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎 HTTP 客户端测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class EngineClientTests {

    private HttpServer server;
    private EngineClient client;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Map<String, String>> request = new AtomicReference<>();
    private volatile String response = "{\"busy\":[\"session-2\",\"session-2\"]}";
    private volatile int status = 200;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/sessions/busy", exchange -> {
            requests.incrementAndGet();
            request.set(Map.of(
                    "method", exchange.getRequestMethod(),
                    "path", exchange.getRequestURI().getPath(),
                    "token", exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                    "contentType", exchange.getRequestHeaders().getFirst("Content-Type"),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        client = new EngineClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void busySessionsPostsAuthenticatedJsonAndReturnsSet() {
        assertEquals(Set.of("session-2"), client.busySessions(List.of("session-1", "session-2")));
        assertEquals(1, requests.get());
        assertEquals("POST", request.get().get("method"));
        assertEquals("/internal/sessions/busy", request.get().get("path"));
        assertEquals("test-token", request.get().get("token"));
        assertTrue(request.get().get("contentType").startsWith("application/json"));
        var mapper = new ObjectMapper();
        assertEquals(mapper.readTree("{\"session_ids\":[\"session-1\",\"session-2\"]}"),
                mapper.readTree(request.get().get("body")));
    }

    @Test
    void emptySessionIdsDoNotSendRequest() {
        assertEquals(Set.of(), client.busySessions(List.of()));
        assertEquals(0, requests.get());
    }

    @Test
    void emptyBusyArrayReturnsEmptySet() {
        response = "{\"busy\":[]}";
        assertEquals(Set.of(), client.busySessions(List.of("session-1")));
        assertEquals(1, requests.get());
    }

    @Test
    void engineFailureIsPropagated() {
        status = 503;
        assertThrows(HttpServerErrorException.class, () -> client.busySessions(List.of("session-1")));
        assertEquals(1, requests.get());
    }
}
