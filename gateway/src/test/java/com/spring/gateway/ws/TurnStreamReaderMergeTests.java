package com.spring.gateway.ws;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事件流增量合并测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
class TurnStreamReaderMergeTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mergesConsecutiveDeltasIntoOneFrame() throws Exception {
        String key = "stream:session:s1";
        MapRecord<String, Object, Object> first = (MapRecord) StreamRecords.newRecord()
                .in(key).withId(RecordId.of("2-0"))
                .ofMap(Map.of("event", "{\"kind\":\"thinking_delta\",\"text\":\"ab\"}"));
        MapRecord<String, Object, Object> second = (MapRecord) StreamRecords.newRecord()
                .in(key).withId(RecordId.of("3-0"))
                .ofMap(Map.of("event", "{\"kind\":\"thinking_delta\",\"text\":\"cd\"}"));
        MapRecord<String, Object, Object> finished = (MapRecord) StreamRecords.newRecord()
                .in(key).withId(RecordId.of("4-0"))
                .ofMap(Map.of("event", "{\"kind\":\"turn_finished\",\"cancelled\":false}"));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        doReturn(ops).when(redis).opsForStream();
        when(ops.read(any(StreamReadOptions.class), any(StreamOffset[].class)))
                .thenReturn(List.of(first, second, finished))
                .thenReturn(null);

        AtomicBoolean open = new AtomicBoolean(true);
        List<String> sent = new ArrayList<>();
        WebSocketSession browser = mock(WebSocketSession.class);
        when(browser.getId()).thenReturn("browser");
        when(browser.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(browser).sendMessage(any(TextMessage.class));

        TurnStreamReader reader = new TurnStreamReader(redis, mapper);
        reader.subscribe(browser, "s1", "0-0");
        for (int i = 0; i < 200 && sent.size() < 2; i++) {
            Thread.sleep(10);
        }
        open.set(false);
        reader.closeFor(browser);
        reader.shutdown();

        assertEquals(2, sent.size());
        JsonNode merged = mapper.readTree(sent.get(0)).get("params");
        assertEquals("3-0", merged.get("seq").asText());
        assertEquals("thinking_delta", merged.get("event").get("kind").asText());
        assertEquals("abcd", merged.get("event").get("text").asText());
        JsonNode tail = mapper.readTree(sent.get(1)).get("params");
        assertEquals("4-0", tail.get("seq").asText());
        assertEquals("turn_finished", tail.get("event").get("kind").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cursorMustStillExistInStream() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        doReturn(ops).when(redis).opsForStream();
        when(ops.range(eq("stream:session:s1"), eq(Range.closed("2-0", "2-0")), any(Limit.class)))
                .thenReturn(List.of(record("2-0", "{\"kind\":\"text_delta\",\"text\":\"a\"}")))
                .thenReturn(List.of())
                .thenReturn(null);

        TurnStreamReader reader = new TurnStreamReader(redis, mapper);
        try {
            assertTrue(reader.canResume("s1", "2-0"));
            assertFalse(reader.canResume("s1", "2-0"));
            assertFalse(reader.canResume("s1", "2-0"));
        } finally {
            reader.shutdown();
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void replayPacesBatchAndCharacterLimitFlushes() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        doReturn(ops).when(redis).opsForStream();
        List<MapRecord<String, Object, Object>> first = new ArrayList<>();
        List<MapRecord<String, Object, Object>> second = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            first.add(record(i + "-0", "{\"kind\":\"thinking_delta\",\"text\":\"x\"}"));
            second.add(record((i + 500) + "-0", "{\"kind\":\"thinking_delta\",\"text\":\"x\"}"));
        }
        when(ops.read(any(StreamReadOptions.class), any(StreamOffset[].class)))
                .thenReturn(first, second, List.of(
                        record("1001-0", mapper.writeValueAsString(Map.of(
                                "kind", "thinking_delta", "text", "x".repeat(32 * 1024)))),
                        record("1002-0", "{\"kind\":\"turn_finished\"}")))
                .thenReturn(null);

        AtomicBoolean open = new AtomicBoolean(true);
        List<String> sent = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        WebSocketSession browser = browser(open);
        doAnswer(invocation -> {
            times.add(System.nanoTime());
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            if (sent.size() == 4) open.set(false);
            return null;
        }).when(browser).sendMessage(any(TextMessage.class));

        TurnStreamReader reader = new TurnStreamReader(redis, mapper);
        try {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    reader, "readLoop", browser, "s1", "0-0");
        } finally {
            reader.shutdown();
        }

        assertEquals(4, sent.size());
        assertEquals(List.of("500-0", "1000-0", "1001-0", "1002-0"), sequences(sent));
        assertEquals("x".repeat(500), mapper.readTree(sent.get(0)).path("params").path("event").path("text").asText());
        assertEquals("x".repeat(500), mapper.readTree(sent.get(1)).path("params").path("event").path("text").asText());
        assertEquals(32 * 1024, mapper.readTree(sent.get(2)).path("params").path("event").path("text").asText().length());
        assertPaced(times);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void initialReplayPacesNonDeltaFramesAndPreservesToolBoundaries() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        doReturn(ops).when(redis).opsForStream();
        when(ops.reverseRange(eq("stream:session:s1"), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(record("1-0", "{\"kind\":\"turn_finished\"}")));
        when(ops.read(any(StreamReadOptions.class), any(StreamOffset[].class)))
                .thenReturn(List.of(
                        record("2-0", "{\"kind\":\"tool_started\",\"tool_call_id\":\"t1\",\"tool_name\":\"read_file\"}"),
                        record("3-0", "{\"kind\":\"tool_args_delta\",\"tool_call_id\":\"t1\",\"args_chunk\":\"ab\"}"),
                        record("4-0", "{\"kind\":\"tool_args_delta\",\"tool_call_id\":\"t1\",\"args_chunk\":\"cd\"}"),
                        record("5-0", "{\"kind\":\"tool_args_delta\",\"tool_call_id\":\"t2\",\"args_chunk\":\"ef\"}"),
                        record("6-0", "{\"kind\":\"turn_finished\"}")))
                .thenReturn(null);

        AtomicBoolean open = new AtomicBoolean(true);
        List<String> sent = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        WebSocketSession browser = browser(open);
        doAnswer(invocation -> {
            times.add(System.nanoTime());
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            if (sent.size() == 4) open.set(false);
            return null;
        }).when(browser).sendMessage(any(TextMessage.class));

        TurnStreamReader reader = new TurnStreamReader(redis, mapper);
        try {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    reader, "readLoop", browser, "s1", null);
        } finally {
            reader.shutdown();
        }

        assertEquals(List.of("2-0", "4-0", "5-0", "6-0"), sequences(sent));
        JsonNode firstArgs = mapper.readTree(sent.get(1)).path("params").path("event");
        assertEquals("t1", firstArgs.path("tool_call_id").asText());
        assertEquals("abcd", firstArgs.path("args_chunk").asText());
        assertEquals("t2", mapper.readTree(sent.get(2)).path("params").path("event").path("tool_call_id").asText());
        assertPaced(times);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void interruptedReplayStopsBeforeNextFrame() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        doReturn(ops).when(redis).opsForStream();
        when(ops.read(any(StreamReadOptions.class), any(StreamOffset[].class)))
                .thenReturn(List.of(
                        record("2-0", "{\"kind\":\"thinking_delta\",\"text\":\"a\"}"),
                        record("3-0", "{\"kind\":\"turn_finished\"}")));
        WebSocketSession browser = browser(new AtomicBoolean(true));
        List<String> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            Thread.currentThread().interrupt();
            return null;
        }).when(browser).sendMessage(any(TextMessage.class));

        TurnStreamReader reader = new TurnStreamReader(redis, mapper);
        try {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    reader, "readLoop", browser, "s1", "1-0");
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("2-0"), sequences(sent));
        } finally {
            Thread.interrupted();
            reader.shutdown();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MapRecord<String, Object, Object> record(String seq, String event) {
        return (MapRecord) StreamRecords.newRecord().in("stream:session:s1")
                .withId(RecordId.of(seq)).ofMap(Map.of("event", event));
    }

    private WebSocketSession browser(AtomicBoolean open) {
        WebSocketSession browser = mock(WebSocketSession.class);
        when(browser.getId()).thenReturn("browser");
        when(browser.isOpen()).thenAnswer(invocation -> open.get());
        return browser;
    }

    private List<String> sequences(List<String> frames) throws Exception {
        List<String> result = new ArrayList<>();
        for (String frame : frames) {
            result.add(mapper.readTree(frame).path("params").path("seq").asText());
        }
        return result;
    }

    private void assertPaced(List<Long> times) {
        for (int i = 1; i < times.size(); i++) {
            assertTrue(times.get(i) - times.get(i - 1) >= TimeUnit.MILLISECONDS.toNanos(25));
        }
    }
}
