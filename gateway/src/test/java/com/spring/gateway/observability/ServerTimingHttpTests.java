package com.spring.gateway.observability;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.InternalAuthInterceptor;
import io.github.sercasti.tracing.core.Tracing;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 HTTP 响应的计时头测试。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server-timing-tests")
@Import(ServerTimingHttpTests.Endpoints.class)
class ServerTimingHttpTests {
    @Value("${local.server.port}")
    private int port;
    @Value("${app.internal-token}")
    private String internalToken;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String timings(HttpResponse<?> response) {
        var headers = response.headers().allValues(Tracing.SERVER_TIMING_HEADER);
        assertEquals(1, headers.size());
        String header = headers.getFirst();
        assertTrue(header.startsWith("total;"), header);
        for (String entry : header.split(",")) {
            assertTrue(entry.matches("[A-Za-z][A-Za-z0-9_-]*;desc=\"[^\"]*\";dur=[0-9]+(?:\\.[0-9]+)?"), entry);
        }
        return header;
    }

    @Test
    void actualPublicControllerHasControllerAndServiceTimings() throws Exception {
        HttpResponse<String> response = get("/api/auth/public-config");
        assertEquals(200, response.statusCode());
        String header = timings(response);
        assertTrue(header.contains("controller;"), header);
        assertTrue(header.contains("service;"), header);
        assertTrue(response.body().contains("mailVerify"), response.body());
    }

    @Test
    void authRejectionsAndValidationErrorsHaveTimings() throws Exception {
        for (String path : new String[]{"/api/account/me", "/api/admin/model-config", "/internal/users/7/sessions"}) {
            HttpResponse<String> response = get(path);
            assertEquals(401, response.statusCode(), path);
            assertTrue(timings(response).contains("auth;"), path);
            assertFalse(timings(response).contains("controller;"), path);
        }
        HttpResponse<String> invalid = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")) .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalid.statusCode());
        timings(invalid);
    }

    @Test
    void internalQueryIncludesRealMybatisExecution() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/internal/users/7/sessions"))
                .header(InternalAuthInterceptor.HEADER_TOKEN, internalToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String header = timings(response);
        assertTrue(header.contains("auth;"), header);
        assertTrue(header.contains("controller;"), header);
        assertTrue(header.contains("service;"), header);
        assertTrue(header.contains("db;"), header);
        assertEquals("[]", response.body());
    }

    @Test
    void loginSelectOneIncludesMybatisPlusCursorExecution() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"timing_missing_user\",\"password\":\"Local-timing-test!\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertTrue(timings(response).contains("db;desc=\"MyBatis queryCursor\""));
    }

    @Test
    void largeAndDirectResponsesRetainHeadersAndExactBodies() throws Exception {
        for (String kind : new String[]{"bytes", "writer"}) {
            HttpResponse<String> response = get("/timing-test/" + kind);
            assertEquals(200, response.statusCode());
            assertEquals("x".repeat(128 * 1024), response.body());
            assertTrue(timings(response).contains("controller;"));
            assertEquals("retained", response.headers().firstValue("X-Test").orElseThrow());
        }
    }

    @Test
    void noBodyRedirectAndExceptionsDoNotLoseHeaders() throws Exception {
        assertEquals(204, get("/timing-test/empty").statusCode());
        timings(get("/timing-test/empty"));
        HttpResponse<String> redirect = get("/timing-test/redirect");
        assertEquals(302, redirect.statusCode());
        timings(redirect);
        assertTrue(redirect.headers().firstValue("Location").orElseThrow().endsWith("/login.html"));
        HttpResponse<String> business = get("/timing-test/business-error");
        assertEquals(409, business.statusCode());
        timings(business);
        HttpResponse<String> unknown = get("/timing-test/error");
        assertEquals(500, unknown.statusCode());
        timings(unknown);
        assertFalse(unknown.body().contains("private-detail"));
    }

    @Test
    void callableDispatchPreservesRequestTimings() throws Exception {
        HttpResponse<String> response = get("/timing-test/async");
        assertEquals(200, response.statusCode());
        assertEquals("async-body", response.body());
        assertTrue(timings(response).contains("async_work;"));
        assertNull(RequestTimings.current());
    }

    @Test
    void fileAndPageRestRoutesOutsideApiPrefixHaveTimings() throws Exception {
        HttpResponse<String> redirect = get("/page/0");
        assertEquals(301, redirect.statusCode());
        assertTrue(timings(redirect).contains("controller;"));
        for (String path : new String[]{"/page/0/", "/0.txt"}) {
            HttpResponse<String> response = get(path);
            assertEquals(404, response.statusCode());
            assertTrue(timings(response).contains("controller;"), path);
            assertTrue(timings(response).contains("service;"), path);
        }
        HttpResponse<String> head = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/auth/public-config"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        timings(head);
    }

    @TestConfiguration
    static class Endpoints {
        @Bean
        TimingController timingController() {
            return new TimingController();
        }
    }

    /**
     * HTTP 提交场景测试接口。
     *
     * @author hanbing
     * @since 2026-10-24
     */
    @RestController
    @org.springframework.boot.test.context.TestComponent
    @Profile("server-timing-tests")
    @RequestMapping("/timing-test")
    public static class TimingController {
        @GetMapping("/bytes")
        public byte[] bytes(HttpServletResponse response) {
            response.setHeader("X-Test", "retained");
            return "x".repeat(128 * 1024).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @GetMapping("/writer")
        public void writer(HttpServletResponse response) throws IOException {
            response.setHeader("X-Test", "retained");
            response.getWriter().write("x".repeat(128 * 1024));
            response.flushBuffer();
        }

        @GetMapping("/empty")
        public void empty(HttpServletResponse response) {
            response.setStatus(204);
        }

        @GetMapping("/redirect")
        public void redirect(HttpServletResponse response) throws IOException {
            response.sendRedirect("/login.html");
        }

        @GetMapping("/business-error")
        public Map<String, Object> businessError() {
            throw new BizException(409, "business-error");
        }

        @GetMapping("/error")
        public Map<String, Object> error() {
            throw new IllegalStateException("private-detail");
        }

        @GetMapping("/async")
        public Callable<String> async() {
            return () -> {
                try (RequestTimings.Span ignored = RequestTimings.start("async_work", "Callable")) {
                    return "async-body";
                }
            };
        }
    }
}
