package com.spring.gateway.ws;

import com.spring.gateway.service.EngineClient;
import com.spring.gateway.service.InternalStoreService;
import com.spring.gateway.service.SessionHistoryService;
import com.spring.gateway.service.TurnDispatchService;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话控制权拦截测试。
 *
 * @author hanbing
 * @since 2026-10-13
 */
class SessionControlTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EngineClient engineClient = mock(EngineClient.class);
    private final TurnDispatchService dispatch = mock(TurnDispatchService.class);
    private final TurnStreamReader streams = mock(TurnStreamReader.class);
    private final EngineRelayHandler relay = new EngineRelayHandler(
            "http://localhost:8001", mapper, dispatch, streams,
            mock(InternalStoreService.class), mock(SessionHistoryService.class),
            mock(WebSocketContainer.class), engineClient, "test-internal-token");
    private final List<String> responses = new ArrayList<>();

    private WebSocketSession browser() throws Exception {
        WebSocketSession browser = mock(WebSocketSession.class);
        var attributes = new HashMap<String, Object>();
        attributes.put(AuthHandshakeInterceptor.ATTR_USER_ID, 123L);
        attributes.put("controlConnectionId", "browser-id");
        when(browser.getAttributes()).thenReturn(attributes);
        when(browser.getId()).thenReturn("browser-id");
        when(browser.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            responses.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(browser).sendMessage(any(TextMessage.class));
        return browser;
    }

    private TextMessage frame(String method) {
        return new TextMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method
                + "\",\"params\":{\"sessionId\":\"session-1\",\"input\":\"hello\"}}");
    }

    @Test
    void missingControlCannotStartCancelOrSubscribe() throws Exception {
        var browser = browser();
        for (String method : List.of("turn/start", "turn/cancel", "stream/subscribe")) {
            relay.handleTextMessage(browser, frame(method));
        }
        verifyNoInteractions(dispatch, streams);
        assertEquals(3, responses.size());
        for (String response : responses) {
            assertEquals(-32003, mapper.readTree(response).path("error").path("code").asInt());
        }
    }

    @Test
    void authorizedControlTokenIsPassedToDispatchAndCancel() throws Exception {
        var browser = browser();
        when(engineClient.sessionControlToken("session-1", 123L, "browser-id")).thenReturn("control-token");
        relay.handleTextMessage(browser, frame("turn/start"));
        relay.handleTextMessage(browser, frame("turn/cancel"));
        relay.handleTextMessage(browser, frame("stream/subscribe"));
        verify(dispatch).dispatch(123L, "session-1", "hello", "control-token");
        verify(dispatch).cancel(123L, "session-1", "control-token");
        verify(streams).subscribe(browser, "session-1", null);
        for (String response : responses) {
            assertEquals(true, mapper.readTree(response).has("result"));
        }
    }

    @Test
    void controlCheckFailureDoesNotFallBackToEngineOrDispatch() throws Exception {
        var browser = browser();
        var upstream = mock(WebSocketSession.class);
        when(upstream.isOpen()).thenReturn(true);
        browser.getAttributes().put("engineSession", upstream);
        when(engineClient.sessionControlToken(anyString(), eq(123L), anyString()))
                .thenThrow(new IllegalStateException("引擎不可达"));
        relay.handleTextMessage(browser, frame("turn/start"));
        verifyNoInteractions(dispatch);
        verify(upstream, org.mockito.Mockito.never()).sendMessage(any());
        assertEquals(-32603, mapper.readTree(responses.getFirst()).path("error").path("code").asInt());
    }
}
