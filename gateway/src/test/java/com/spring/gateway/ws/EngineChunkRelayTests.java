package com.spring.gateway.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 引擎大帧分片中继测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
class EngineChunkRelayTests {

    private final ObjectMapper mapper = new ObjectMapper();

    private final List<String> sent = new ArrayList<>();

    private WebSocketSession browser() throws Exception {
        WebSocketSession browser = mock(WebSocketSession.class);
        when(browser.getId()).thenReturn("browser");
        when(browser.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(browser).sendMessage(any(TextMessage.class));
        return browser;
    }

    private static final class ExposedHandler extends EngineRelayHandler.EngineToBrowserHandler {

        ExposedHandler(WebSocketSession browser, ObjectMapper mapper) {
            super(browser, mapper);
        }

        void feed(String payload) throws Exception {
            handleTextMessage(null, new TextMessage(payload));
        }
    }

    private String chunk(String id, int index, int total, String data) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "__ws_chunk",
                "params", Map.of("id", id, "index", index, "total", total, "data", data)));
    }

    @Test
    void reassemblesAllChunksBeforeForwarding() throws Exception {
        WebSocketSession browser = browser();
        ExposedHandler handler = new ExposedHandler(browser, mapper);

        String payload = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"text\":\""
                + "x".repeat(20_000) + "\"}}";
        int size = 4096;
        int total = (payload.length() + size - 1) / size;
        for (int i = 0; i < total; i++) {
            handler.feed(chunk("c1", i, total,
                    payload.substring(i * size, Math.min(payload.length(), (i + 1) * size))));
            if (i < total - 1) {
                assertTrue(sent.isEmpty(), "分片未收齐前不应下发");
            }
        }

        assertEquals(List.of(payload), sent);
    }

    @Test
    void forwardsPlainFrameUnchanged() throws Exception {
        WebSocketSession browser = browser();
        ExposedHandler handler = new ExposedHandler(browser, mapper);

        String plain = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
        handler.feed(plain);

        assertEquals(List.of(plain), sent);
    }
}
