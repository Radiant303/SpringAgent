package com.spring.gateway.observability;

import io.github.sercasti.tracing.core.Tracing;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计时过滤器的响应边界测试。
 *
 * @author hanbing
 * @since 2026-10-24
 */
class ServerTimingFilterTests {
    private final ServerTimingFilter filter = new ServerTimingFilter();

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private static String header(MockHttpServletResponse response) {
        String value = response.getHeader(Tracing.SERVER_TIMING_HEADER);
        assertNotNull(value);
        for (String entry : value.split(",")) {
            assertTrue(entry.matches("[A-Za-z][A-Za-z0-9_-]*;desc=\"[^\"]*\";dur=[0-9]+(?:\\.[0-9]+)?"), entry);
        }
        return value;
    }

    @Test
    void writesBeforeOutputAndDoesNotCacheOrChangeBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                ServletOutputStream delegate = super.getOutputStream();
                return new ServletOutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        assertNotNull(getHeader(Tracing.SERVER_TIMING_HEADER));
                        delegate.write(value);
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        assertNotNull(getHeader(Tracing.SERVER_TIMING_HEADER));
                        assertFalse(isCommitted());
                        delegate.write(bytes, offset, length);
                    }

                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        delegate.setWriteListener(listener);
                    }
                };
            }
        };
        byte[] bytes = "a".repeat(128 * 1024).getBytes(StandardCharsets.UTF_8);
        filter.doFilter(request("/api/test"), response, (req, res) -> {
            try (RequestTimings.Span ignored = RequestTimings.start("service", "test")) {
                res.getOutputStream().write(bytes);
                assertArrayEquals(bytes, response.getContentAsByteArray());
            }
        });
        assertArrayEquals(bytes, response.getContentAsByteArray());
        assertTrue(header(response).contains("service;"));
        assertNull(RequestTimings.current());
    }

    @Test
    void writerPreservesEncodingAndHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Test", "preserved");
        filter.doFilter(request("/internal/test"), response, (req, res) -> {
            res.getWriter().write("中文响应");
            res.getWriter().flush();
        });
        assertEquals("中文响应", response.getContentAsString());
        assertEquals("preserved", response.getHeader("X-Test"));
        header(response);
    }

    @Test
    void emptyBodyAndHeadStillHaveTimings() throws Exception {
        for (String method : List.of("GET", "HEAD")) {
            MockHttpServletRequest request = request("/api/test");
            request.setMethod(method);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(204));
            assertEquals(204, response.getStatus());
            assertEquals(0, response.getContentAsByteArray().length);
            header(response);
        }
    }

    @Test
    void addsHeadersBeforeRedirectErrorFlushAndClose() throws Exception {
        for (String action : List.of("redirect", "redirectStatus", "redirectBuffer", "redirectAll", "error", "errorMessage", "flush", "close")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("/api/test"), response, (req, res) -> {
                HttpServletResponse http = (HttpServletResponse) res;
                switch (action) {
                    case "redirect" -> http.sendRedirect("/login");
                    case "redirectStatus" -> http.sendRedirect("/login", 307);
                    case "redirectBuffer" -> http.sendRedirect("/login", true);
                    case "redirectAll" -> http.sendRedirect("/login", 308, false);
                    case "error" -> http.sendError(503);
                    case "errorMessage" -> http.sendError(502, "unavailable");
                    case "flush" -> http.flushBuffer();
                    case "close" -> http.getOutputStream().close();
                    default -> throw new AssertionError(action);
                }
                header(response);
            });
            assertEquals(1, response.getHeaders(Tracing.SERVER_TIMING_HEADER).size(), action);
        }
    }

    @Test
    void resetsRestoreTimingHeaderWithoutDuplicatingIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/test"), response, (req, res) -> {
            res.getWriter().write("old");
            res.reset();
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("new");
            res.getWriter().flush();
        });
        assertEquals("new", response.getContentAsString());
        assertEquals(1, response.getHeaders(Tracing.SERVER_TIMING_HEADER).size());
    }

    @Test
    void failuresCleanThreadStateAndDoNotLeakMetrics() throws Exception {
        MockHttpServletResponse failed = new MockHttpServletResponse();
        assertThrows(ServletException.class, () -> filter.doFilter(request("/api/test"), failed, (req, res) -> {
            try (RequestTimings.Span ignored = RequestTimings.start("failed", "failure")) {
                throw new ServletException("failure");
            }
        }));
        assertTrue(header(failed).contains("failed;"));
        assertNull(RequestTimings.current());
        MockHttpServletResponse next = new MockHttpServletResponse();
        filter.doFilter(request("/api/test"), next, (req, res) -> {});
        assertFalse(header(next).contains("failed"));
    }

    @Test
    void ignoresClientTimingAndBoundsMetricCount() throws Exception {
        MockHttpServletRequest request = request("/api/test");
        request.addHeader(Tracing.SERVER_TIMING_HEADER, "forged;dur=999999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            for (int i = 0; i < 500; i++) {
                try (RequestTimings.Span ignored = RequestTimings.start("db", "query")) {
                    assertNotNull(RequestTimings.current());
                }
            }
        });
        String value = header(response);
        assertEquals(RequestTimings.MAX_METRICS, value.split(",").length);
        assertFalse(value.contains("forged"));
        assertTrue(value.length() < 8192);
    }

    @Test
    void descriptionsCannotInjectHeadersAndWorstCaseHeaderIsBounded() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/test"), response, (req, res) -> {
            try (RequestTimings.Span ignored = RequestTimings.start("bad\r\nname", "discard")) {
                for (int i = 0; i < 100; i++) {
                    try (RequestTimings.Span nested = RequestTimings.start("a".repeat(32), "\"".repeat(200))) {
                        assertNotNull(RequestTimings.current());
                    }
                }
            }
        });
        String value = response.getHeader(Tracing.SERVER_TIMING_HEADER);
        assertNotNull(value);
        assertFalse(value.contains("\r"));
        assertFalse(value.contains("\n"));
        assertFalse(value.contains("bad"));
        assertTrue(value.contains("\\\""));
        assertTrue(value.length() < 8192);
        assertEquals(RequestTimings.MAX_METRICS, value.split(",").length);
    }

    @Test
    void skipsStaticAndWebsocketAndEnablesNonApiRestHandlers() throws Exception {
        for (String path : List.of("/static/style.css", "/ws", "/index.html")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(path), response, (req, res) -> res.getWriter().write("body"));
            assertNull(response.getHeader(Tracing.SERVER_TIMING_HEADER), path);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/123.txt"), response, (req, res) -> {
            RequestTimings.current().enable();
            res.getOutputStream().write(65);
        });
        header(response);
    }

    @Test
    void concurrentRequestsHaveIndependentMetrics() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 20).mapToObj(index -> (Callable<String>) () -> {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(request("/api/test"), response, (req, res) -> {
                    try (RequestTimings.Span ignored = RequestTimings.start("unique_" + index, "test")) {
                        res.getWriter().write("ok");
                    }
                });
                assertNull(RequestTimings.current());
                return header(response);
            }).toList();
            var results = executor.invokeAll(tasks);
            for (int i = 0; i < results.size(); i++) {
                String value = results.get(i).get();
                assertTrue(value.contains("unique_" + i + ";"));
                assertEquals(2, value.split(",").length);
            }
        }
    }

    @Test
    void asyncRedispatchReusesRequestContext() throws Exception {
        MockHttpServletRequest request = request("/api/test");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> request.startAsync(req, res));
        assertNull(response.getHeader(Tracing.SERVER_TIMING_HEADER));
        assertNull(RequestTimings.current());
        request.getAsyncContext().complete();
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, response, (req, res) -> res.getWriter().write("async"));
        header(response);
        assertNull(RequestTimings.current());
    }

    @Test
    void callsOutsideRequestAreNoop() {
        assertDoesNotThrow(() -> {
            try (RequestTimings.Span ignored = RequestTimings.start("outside", "test")) {
                assertNull(RequestTimings.current());
            }
        });
    }
}
