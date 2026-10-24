package com.spring.gateway.ws;

import com.spring.gateway.workspace.service.SessionStreamControl;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Range.Bound;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 事件流推送器：按订阅从 Redis Stream 读会话事件，推给浏览器 WS。
 *
 * @author hanbing
 * @since 2026-10-02
 */
@Slf4j
@Component
public class TurnStreamReader implements SessionStreamControl {

    private static final String KEY_PREFIX = "stream:session:";
    private static final int READ_BATCH = 500;
    /** 阻塞读等待上限 */
    private static final Duration READ_BLOCK = Duration.ofMillis(50);
    private static final long MERGE_WINDOW_MS = 10;
    /** 单帧合并字符数上限 */
    private static final int MERGE_MAX_CHARS = 32 * 1024;
    private static final int MERGE_MAX_EVENTS = 500;
    private static final long ERROR_BACKOFF_MS = 1000;
    /** 反向扫描每页条目数（找最近一条 turn_finished） */
    private static final int SCAN_PAGE = 500;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    /** 浏览器连接 ID → (会话 ID → 读取任务) */
    private final Map<String, Map<String, Future<?>>> readers = new ConcurrentHashMap<>();

    public TurnStreamReader(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "turn-stream-reader");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 订阅会话事件流。
     *
     * @param browserSession 浏览器 WS 会话
     * @param sessionId      会话 ID
     * @param lastSeq        续读游标（Stream 条目 ID），null 表示只收新事件
     */
    public void subscribe(WebSocketSession browserSession, String sessionId, String lastSeq) {
        readers.compute(browserSession.getId(), (key, existing) -> {
            Map<String, Future<?>> bySession = existing == null ? new ConcurrentHashMap<>() : existing;
            Future<?> previous = bySession.remove(sessionId);
            if (previous != null) {
                previous.cancel(true);
            }
            bySession.put(sessionId, executor.submit(() -> readLoop(browserSession, sessionId, lastSeq)));
            return bySession;
        });
        log.info("stream 订阅: browser={} session={} from={}",
                browserSession.getId(), sessionId, lastSeq == null ? "$" : lastSeq);
    }

    /**
     * 取消会话事件流订阅。
     *
     * @param browserSession 浏览器 WS 会话
     * @param sessionId      会话 ID
     */
    public void unsubscribe(WebSocketSession browserSession, String sessionId) {
        readers.computeIfPresent(browserSession.getId(), (key, bySession) -> {
            Future<?> previous = bySession.remove(sessionId);
            if (previous != null) {
                previous.cancel(true);
            }
            return bySession.isEmpty() ? null : bySession;
        });
    }

    public void closeSession(String sessionId) {
        for (String connectionId : readers.keySet()) {
            readers.computeIfPresent(connectionId, (key, bySession) -> {
                Future<?> previous = bySession.remove(sessionId);
                if (previous != null) previous.cancel(true);
                return bySession.isEmpty() ? null : bySession;
            });
        }
    }

    /** 连接关闭时取消它的全部读取任务 */
    public void closeFor(WebSocketSession browserSession) {
        Map<String, Future<?>> bySession = readers.remove(browserSession.getId());
        if (bySession != null) {
            bySession.values().forEach(future -> future.cancel(true));
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public boolean canResume(String sessionId, String lastSeq) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(
                KEY_PREFIX + sessionId, Range.closed(lastSeq, lastSeq), Limit.limit().count(1));
        return records != null && !records.isEmpty();
    }

    private void readLoop(WebSocketSession browserSession, String sessionId, String lastSeq) {
        String key = KEY_PREFIX + sessionId;
        String offset = lastSeq != null ? lastSeq : findTurnStartOffset(key);
        ReplayPacer replayPacer = offset != null ? new ReplayPacer() : null;
        PendingDelta pending = null;
        try {
            while (browserSession.isOpen() && !Thread.currentThread().isInterrupted()) {
                if (replayPacer == null && pending != null
                        && pending.flushAt - System.currentTimeMillis() <= 0) {
                    pushMerged(browserSession, sessionId, pending, null);
                    pending = null;
                    continue;
                }
                List<MapRecord<String, Object, Object>> records;
                try {
                    StreamReadOptions options = StreamReadOptions.empty().count(READ_BATCH);
                    if (replayPacer == null) {
                        // 有未发增量时把阻塞读超时压到合并窗剩余时间，窗口到期即发出
                        long blockMs = READ_BLOCK.toMillis();
                        if (pending != null) {
                            blockMs = Math.min(blockMs, pending.flushAt - System.currentTimeMillis());
                        }
                        options = options.block(Duration.ofMillis(blockMs));
                    }
                    records = redisTemplate.opsForStream().read(options,
                            StreamOffset.create(key,
                                    offset == null ? ReadOffset.latest() : ReadOffset.from(offset)));
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted() || !browserSession.isOpen()) {
                        break;
                    }
                    log.warn("stream 读取异常: session={} error={}", sessionId, e.getMessage());
                    sleepQuietly();
                    continue;
                }
                if (records == null || records.isEmpty()) {
                    if (pending != null) {
                        pushMerged(browserSession, sessionId, pending, replayPacer);
                        pending = null;
                    }
                    replayPacer = null;
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    if (Thread.currentThread().isInterrupted() || !browserSession.isOpen()) {
                        return;
                    }
                    offset = record.getId().getValue();
                    Object eventJson = record.getValue().get("event");
                    if (eventJson == null) {
                        continue;
                    }
                    JsonNode event;
                    try {
                        event = objectMapper.readTree(eventJson.toString());
                    } catch (Exception e) {
                        log.warn("stream 事件解析失败: session={} seq={}", sessionId, offset);
                        continue;
                    }
                    String kind = textOf(event, "kind");
                    if (!isDelta(kind)) {
                        if (pending != null) {
                            pushMerged(browserSession, sessionId, pending, replayPacer);
                            pending = null;
                        }
                        push(browserSession, sessionId, offset, event, replayPacer);
                        continue;
                    }
                    String toolCallId = textOf(event, "tool_call_id");
                    if (pending == null || !pending.canMerge(kind, toolCallId)) {
                        if (pending != null) {
                            pushMerged(browserSession, sessionId, pending, replayPacer);
                        }
                        pending = new PendingDelta(kind, toolCallId);
                    }
                    pending.add(event, offset);
                    if (pending.chars() >= MERGE_MAX_CHARS || pending.count >= MERGE_MAX_EVENTS) {
                        pushMerged(browserSession, sessionId, pending, replayPacer);
                        pending = null;
                    }
                }
                if (pending != null && System.currentTimeMillis() >= pending.flushAt) {
                    pushMerged(browserSession, sessionId, pending, replayPacer);
                    pending = null;
                }
            }
        } finally {
            if (pending != null) {
                pushMerged(browserSession, sessionId, pending, replayPacer);
            }
        }
    }

    private static boolean isDelta(String kind) {
        return "text_delta".equals(kind) || "thinking_delta".equals(kind)
                || "tool_args_delta".equals(kind);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? "" : value.asText();
    }

    private void pushMerged(WebSocketSession browserSession, String sessionId, PendingDelta pending,
                            ReplayPacer replayPacer) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("kind", pending.kind);
        if ("tool_args_delta".equals(pending.kind)) {
            event.put("tool_call_id", pending.toolCallId);
            event.put("args_chunk", pending.text.toString());
        } else {
            event.put("text", pending.text.toString());
        }
        push(browserSession, sessionId, pending.seq, event, replayPacer);
    }

    private void push(WebSocketSession browserSession, String sessionId, String seq, JsonNode event,
                      ReplayPacer replayPacer) {
        if (replayPacer != null && !replayPacer.awaitNext(browserSession)) return;
        push(browserSession, sessionId, seq, event);
        if (replayPacer != null) replayPacer.sent();
    }

    /**
     * 重放帧发送节奏。
     *
     * @author hanbing
     * @since 2026-10-18
     */
    private static final class ReplayPacer {
        /** 重放帧最小发送间隔。 */
        private static final long FRAME_INTERVAL_NANOS = Duration.ofMillis(30).toNanos();
        private long nextSendAt = System.nanoTime();

        private boolean awaitNext(WebSocketSession browserSession) {
            if (Thread.currentThread().isInterrupted() || !browserSession.isOpen()) return false;
            long wait;
            while ((wait = nextSendAt - System.nanoTime()) > 0) {
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !Thread.currentThread().isInterrupted() && browserSession.isOpen();
        }

        private void sent() {
            nextSendAt = System.nanoTime() + FRAME_INTERVAL_NANOS;
        }
    }

    private static final class PendingDelta {
        private final String kind;
        private final String toolCallId;
        private final StringBuilder text = new StringBuilder();
        private final long flushAt = System.currentTimeMillis() + MERGE_WINDOW_MS;
        private String seq;
        private int count;

        private PendingDelta(String kind, String toolCallId) {
            this.kind = kind;
            this.toolCallId = toolCallId;
        }

        private boolean canMerge(String kind, String toolCallId) {
            return this.kind.equals(kind) && this.toolCallId.equals(toolCallId);
        }

        private void add(JsonNode event, String seq) {
            this.seq = seq;
            this.count++;
            text.append(textOf(event, "tool_args_delta".equals(kind) ? "args_chunk" : "text"));
        }

        private int chars() {
            return text.length();
        }
    }

    /**
     * lastSeq 缺失时确定起始位置：反向扫描找最近一条 turn_finished，从其后重放
     * 当前未完成的 turn；流非空但没有 turn_finished → 返回 "0"（整个流就是当前
     * turn，从头放）；流为空/不存在 → 返回 null（只收新事件）。
     */
    private String findTurnStartOffset(String key) {
        String upperExclusive = null;
        while (true) {
            Range<String> range = upperExclusive == null
                    ? Range.unbounded()
                    : Range.from(Bound.inclusive("-")).to(Bound.exclusive(upperExclusive));
            List<MapRecord<String, Object, Object>> page;
            try {
                page = redisTemplate.opsForStream().reverseRange(key, range, Limit.limit().count(SCAN_PAGE));
            } catch (Exception e) {
                log.warn("stream 反向扫描失败: key={} error={}", key, e.getMessage());
                return null;
            }
            if (page == null || page.isEmpty()) {
                return null;
            }
            for (MapRecord<String, Object, Object> record : page) {  // reverseRange 返回新→旧
                Object eventJson = record.getValue().get("event");
                if (eventJson != null && isTurnFinished(eventJson.toString())) {
                    return record.getId().getValue();
                }
            }
            if (page.size() < SCAN_PAGE) {
                return "0";  // 扫到流头也没见到 turn_finished：流里全是当前 turn
            }
            upperExclusive = page.get(page.size() - 1).getId().getValue();
        }
    }

    private boolean isTurnFinished(String eventJson) {
        try {
            JsonNode kind = objectMapper.readTree(eventJson).get("kind");
            return kind != null && "turn_finished".equals(kind.asText());
        } catch (Exception e) {
            return false;
        }
    }

    private void push(WebSocketSession browserSession, String sessionId, String seq, JsonNode event) {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("sessionId", sessionId);
            params.put("seq", seq);
            params.set("event", event);
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("jsonrpc", "2.0");
            frame.put("method", "session/event");
            frame.set("params", params);
            // 同一浏览器会话存在多个发送方，发送必须串行，避免帧交错
            synchronized (browserSession) {
                if (!Thread.currentThread().isInterrupted() && browserSession.isOpen()) {
                    browserSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
                }
            }
        } catch (Exception e) {
            log.warn("stream 事件推送失败: session={} seq={} error={}", sessionId, seq, e.getMessage());
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(ERROR_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
