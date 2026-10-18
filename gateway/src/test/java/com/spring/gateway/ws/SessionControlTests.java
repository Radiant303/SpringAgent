package com.spring.gateway.ws;

import com.spring.gateway.entity.Session;
import com.spring.gateway.service.EngineClient;
import com.spring.gateway.service.InternalStoreService;
import com.spring.gateway.service.SessionHistoryService;
import com.spring.gateway.service.TurnDispatchService;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private final InternalStoreService store = mock(InternalStoreService.class);
    private final EngineRelayHandler relay = new EngineRelayHandler(
            "http://localhost:8001", mapper, dispatch, streams,
            store, mock(SessionHistoryService.class),
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
    void missingControlCannotStartCancelSubscribeOrUnsubscribe() throws Exception {
        var browser = browser();
        for (String method : List.of("turn/start", "turn/cancel", "stream/subscribe", "stream/unsubscribe")) {
            relay.handleTextMessage(browser, frame(method));
        }
        verifyNoInteractions(dispatch, streams);
        assertEquals(4, responses.size());
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

    @Test
    void validCursorResumesWithoutReset() throws Exception {
        var browser = browser();
        when(engineClient.sessionControlToken("session-1", 123L, "browser-id")).thenReturn("control-token");
        when(streams.canResume("session-1", "1-0")).thenReturn(true);

        relay.handleTextMessage(browser, new TextMessage(
                "{\"id\":1,\"method\":\"stream/subscribe\",\"params\":{\"sessionId\":\"session-1\",\"lastSeq\":\"1-0\"}}"));

        verify(streams).subscribe(browser, "session-1", "1-0");
        verify(streams, never()).unsubscribe(any(), anyString());
        assertTrue(mapper.readTree(responses.getFirst()).path("result").isEmpty());
    }

    @Test
    void expiredCursorRequiresHistoryReloadWithoutStartingReader() throws Exception {
        var browser = browser();
        when(engineClient.sessionControlToken("session-1", 123L, "browser-id")).thenReturn("control-token");
        when(streams.canResume("session-1", "1-0")).thenReturn(false);

        relay.handleTextMessage(browser, new TextMessage(
                "{\"id\":1,\"method\":\"stream/subscribe\",\"params\":{\"sessionId\":\"session-1\",\"lastSeq\":\"1-0\"}}"));

        verify(streams, never()).subscribe(any(), anyString(), any());
        verify(streams).unsubscribe(browser, "session-1");
        assertTrue(mapper.readTree(responses.getFirst()).path("result").path("resetRequired").asBoolean());
    }

    @Test
    void cursorValidationFailureReturnsErrorWithoutStartingReader() throws Exception {
        var browser = browser();
        when(engineClient.sessionControlToken("session-1", 123L, "browser-id")).thenReturn("control-token");
        when(streams.canResume("session-1", "1-0")).thenThrow(new IllegalStateException("Redis 不可用"));

        relay.handleTextMessage(browser, new TextMessage(
                "{\"id\":1,\"method\":\"stream/subscribe\",\"params\":{\"sessionId\":\"session-1\",\"lastSeq\":\"1-0\"}}"));

        verify(streams, never()).subscribe(any(), anyString(), any());
        assertEquals(-32603, mapper.readTree(responses.getFirst()).path("error").path("code").asInt());
    }

    @Test
    void malformedCursorIsRejectedBeforeReadingRedis() throws Exception {
        var browser = browser();
        when(engineClient.sessionControlToken("session-1", 123L, "browser-id")).thenReturn("control-token");
        for (String cursor : List.of("\"\"", "\"$\"", "\"1\"", "123", "{}")) {
            relay.handleTextMessage(browser, new TextMessage(
                    "{\"id\":1,\"method\":\"stream/subscribe\",\"params\":{\"sessionId\":\"session-1\",\"lastSeq\":"
                            + cursor + "}}"));
        }

        verifyNoInteractions(streams);
        assertEquals(5, responses.size());
        for (String response : responses) {
            assertEquals(-32602, mapper.readTree(response).path("error").path("code").asInt());
        }
    }

    @Test
    void subscriptionsCoexistAndUnsubscribeOnlyCancelsTarget() throws Exception {
        var browser = browser();
        var executor = mock(ExecutorService.class);
        var first = mock(Future.class);
        var second = mock(Future.class);
        doReturn(first, second).when(executor).submit(any(Runnable.class));
        var reader = reader(executor);
        var handler = new EngineRelayHandler(
                "http://localhost:8001", mapper, dispatch, reader, store,
                mock(SessionHistoryService.class), mock(WebSocketContainer.class),
                engineClient, "test-internal-token");
        when(engineClient.sessionControlToken(anyString(), eq(123L), eq("browser-id")))
                .thenReturn("control-token");

        handler.handleTextMessage(browser, frame("stream/subscribe"));
        handler.handleTextMessage(browser, new TextMessage(
                "{\"id\":2,\"method\":\"stream/subscribe\",\"params\":{\"sessionId\":\"session-2\"}}"));

        var readers = (Map<?, ?>) ReflectionTestUtils.getField(reader, "readers");
        assertEquals(2, ((Map<?, ?>) readers.get("browser-id")).size());
        verify(first, never()).cancel(true);
        verify(second, never()).cancel(true);
        assertEquals(2, responses.size());
        for (String response : responses) {
            assertTrue(mapper.readTree(response).has("result"));
            assertTrue(mapper.readTree(response).path("result").isEmpty());
        }

        handler.handleTextMessage(browser, frame("stream/unsubscribe"));
        verify(first).cancel(true);
        verify(second, never()).cancel(true);
        assertEquals(Set.of("session-2"), ((Map<?, ?>) readers.get("browser-id")).keySet());
        assertTrue(mapper.readTree(responses.getLast()).path("result").isEmpty());
        handler.handleTextMessage(browser, frame("stream/unsubscribe"));
        verify(first).cancel(true);
        reader.unsubscribe(browser, "session-2");
        verify(second).cancel(true);
        assertTrue(readers.isEmpty());
        reader.unsubscribe(browser, "session-2");
    }

    @Test
    void resubscribingReplacesOnlySameSessionAndCloseCancelsRemaining() throws Exception {
        var browser = browser();
        var executor = mock(ExecutorService.class);
        var first = mock(Future.class);
        var second = mock(Future.class);
        var replacement = mock(Future.class);
        doReturn(first, second, replacement).when(executor).submit(any(Runnable.class));
        var reader = reader(executor);
        reader.subscribe(browser, "session-1", null);
        reader.subscribe(browser, "session-2", "1-0");
        reader.subscribe(browser, "session-1", "2-0");
        verify(first).cancel(true);
        verify(second, never()).cancel(true);
        verify(replacement, never()).cancel(true);
        reader.closeFor(browser);
        verify(second).cancel(true);
        verify(replacement).cancel(true);
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(reader, "readers")).isEmpty());
    }

    @Test
    void unsubscribeRejectsMissingBlankAndNonTextSessionIds() throws Exception {
        var browser = browser();
        for (String params : List.of("null", "{}", "{\"sessionId\":\" \"}", "{\"sessionId\":123}")) {
            relay.handleTextMessage(browser, new TextMessage(
                    "{\"id\":1,\"method\":\"stream/unsubscribe\",\"params\":" + params + "}"));
        }
        verifyNoInteractions(engineClient, streams, dispatch);
        assertEquals(4, responses.size());
        for (String response : responses) {
            assertEquals(-32602, mapper.readTree(response).path("error").path("code").asInt());
        }
    }

    @Test
    void sessionListIncludesBusyAndPreservesWorkFilterAndMetadata() throws Exception {
        var browser = browser();
        when(store.listUserSessions(123L, "work-1"))
                .thenReturn(List.of(session("session-1"), session("session-2")));
        when(engineClient.busySessions(List.of("session-1", "session-2"))).thenReturn(Set.of("session-1"));
        relay.handleTextMessage(browser, new TextMessage(
                "{\"id\":1,\"method\":\"session/list\",\"params\":{\"workId\":\"work-1\"}}"));
        var sessions = mapper.readTree(responses.getFirst()).path("result").path("sessions");
        assertEquals(2, sessions.size());
        assertTrue(sessions.get(0).path("busy").asBoolean());
        assertFalse(sessions.get(1).path("busy").asBoolean(true));
        assertEquals("session-1", sessions.get(0).path("sessionId").asText());
        assertEquals("work-1", sessions.get(0).path("workId").asText());
        assertEquals("标题", sessions.get(0).path("title").asText());
        assertEquals("2026-10-17T12:00:00Z", sessions.get(0).path("createdAt").asText());
        assertEquals("2026-10-17T12:00:00Z", sessions.get(0).path("updatedAt").asText());
        verify(engineClient).busySessions(List.of("session-1", "session-2"));
    }

    @Test
    void sessionListDegradesToNotBusyWhenEngineFails() throws Exception {
        var browser = browser();
        when(store.listUserSessions(123L, null))
                .thenReturn(List.of(session("session-1"), session("session-2")));
        when(engineClient.busySessions(List.of("session-1", "session-2")))
                .thenThrow(new IllegalStateException("引擎不可达"));
        relay.handleTextMessage(browser, new TextMessage("{\"id\":1,\"method\":\"session/list\"}"));
        var response = mapper.readTree(responses.getFirst());
        assertFalse(response.has("error"));
        var sessions = response.path("result").path("sessions");
        assertEquals(2, sessions.size());
        for (var item : sessions) {
            assertTrue(item.path("busy").isBoolean());
            assertFalse(item.path("busy").asBoolean(true));
        }
    }

    private TurnStreamReader reader(ExecutorService executor) {
        var reader = new TurnStreamReader(mock(StringRedisTemplate.class), mapper);
        reader.shutdown();
        ReflectionTestUtils.setField(reader, "executor", executor);
        return reader;
    }

    private Session session(String sessionId) {
        var row = new Session();
        row.setId(sessionId);
        row.setWorkId("work-1");
        row.setTitle("标题");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 17, 12, 0));
        row.setUpdatedAt(row.getCreatedAt());
        return row;
    }
}
