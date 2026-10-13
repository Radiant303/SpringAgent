package com.spring.gateway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.StringNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionHistoryService 分页核心的纯逻辑单元测试：不连库、不启 Spring，
 * 直接对包私有的静态方法喂构造的段数据。
 *
 * @author hanbing
 * @since 2026-10-03
 */
class SessionHistoryServiceTests {

    private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- 构造数据的小工具 ----

    private static List<JsonNode> seg(String... texts) {
        List<JsonNode> segment = new ArrayList<>();
        for (String text : texts) {
            segment.add(new StringNode(text));
        }
        return segment;
    }

    private static List<List<JsonNode>> segments(List<JsonNode>... segments) {
        return new ArrayList<>(List.of(segments));
    }

    private static List<String> texts(List<List<JsonNode>> segments) {
        return segments.stream()
                .map(segment -> segment.stream().map(JsonNode::asText).toList())
                .map(Object::toString)
                .toList();
    }

    // ---- 跨段去重 ----

    @Test
    @DisplayName("跨段去重：剥掉与累计前缀重叠的尾部")
    void dedupStripsOverlap() {
        List<List<JsonNode>> result = SessionHistoryService.dedupSegments(
                segments(seg("a", "b", "c"), seg("b", "c", "d")));

        assertEquals(List.of("[a, b, c]", "[d]"), texts(result));
    }

    @Test
    @DisplayName("跨段去重：命中起始点取 s+k 最大者")
    void dedupTakesLargestOverlap() {
        // acc=[p,q,r]，seg 在 s=0 处只有长度 1 的重叠，s=1 处有长度 3 的重叠，应取后者
        List<List<JsonNode>> result = SessionHistoryService.dedupSegments(
                segments(seg("p", "q", "r"), seg("r", "p", "q", "r")));

        assertEquals(List.of("[p, q, r]", "[r]"), texts(result));
    }

    @Test
    @DisplayName("跨段去重：无重叠时段原样保留")
    void dedupKeepsDisjoint() {
        List<List<JsonNode>> result = SessionHistoryService.dedupSegments(
                segments(seg("a"), seg("b"), List.of()));

        assertEquals(List.of("[a]", "[b]", "[]"), texts(result));
    }

    // ---- 分页切片 ----

    @Test
    @DisplayName("forward 翻页：空游标从头切，nextCursor 接力，hasMore 到尾为 false")
    void forwardPaging() {
        List<List<JsonNode>> raw = segments(seg("a", "b", "c", "d", "e"));

        SessionHistoryService.HistoryPage first =
                SessionHistoryService.pageHistory(MAPPER, raw, SESSION_ID, null, 2, "forward");
        assertEquals(List.of("[a, b]"), texts(first.segments()));
        assertEquals(0, first.firstSegmentIndex());
        assertTrue(first.hasMore());
        assertNull(first.previousCursor());

        SessionHistoryService.HistoryPage second = SessionHistoryService.pageHistory(
                MAPPER, raw, SESSION_ID, first.nextCursor(), 2, "forward");
        assertEquals(List.of("[c, d]"), texts(second.segments()));
        assertTrue(second.hasMore());

        SessionHistoryService.HistoryPage third = SessionHistoryService.pageHistory(
                MAPPER, raw, SESSION_ID, second.nextCursor(), 2, "forward");
        assertEquals(List.of("[e]"), texts(third.segments()));
        assertFalse(third.hasMore());
        assertNull(third.nextCursor());
    }

    @Test
    @DisplayName("backward 翻页：空游标从尾部切，previousCursor 接力")
    void backwardPaging() {
        List<List<JsonNode>> raw = segments(seg("a", "b", "c", "d", "e"));

        SessionHistoryService.HistoryPage first =
                SessionHistoryService.pageHistory(MAPPER, raw, SESSION_ID, null, 2, "backward");
        assertEquals(List.of("[d, e]"), texts(first.segments()));
        assertEquals(0, first.firstSegmentIndex());
        assertTrue(first.hasMore());
        assertNull(first.nextCursor());

        SessionHistoryService.HistoryPage second = SessionHistoryService.pageHistory(
                MAPPER, raw, SESSION_ID, first.previousCursor(), 2, "backward");
        assertEquals(List.of("[b, c]"), texts(second.segments()));
        assertTrue(second.hasMore());

        SessionHistoryService.HistoryPage third = SessionHistoryService.pageHistory(
                MAPPER, raw, SESSION_ID, second.previousCursor(), 2, "backward");
        assertEquals(List.of("[a]"), texts(third.segments()));
        assertFalse(third.hasMore());
        assertNull(third.previousCursor());
    }

    @Test
    @DisplayName("跨段切片：窗口覆盖空段时保留空段占位，firstSegmentIndex 指向首个非空切片")
    void slicingKeepsEmptySegment() {
        List<List<JsonNode>> raw = segments(seg("a"), List.of(), seg("b"));

        SessionHistoryService.HistoryPage page =
                SessionHistoryService.pageHistory(MAPPER, raw, SESSION_ID, null, 2, "forward");

        assertEquals(List.of("[a]", "[]", "[b]"), texts(page.segments()));
        assertEquals(0, page.firstSegmentIndex());
        assertFalse(page.hasMore());
    }

    @Test
    @DisplayName("limit=null 返回完整当前历史，此时不接受 cursor")
    void fullHistoryWithoutLimit() {
        List<List<JsonNode>> raw = segments(seg("a", "b"), seg("c"));

        SessionHistoryService.HistoryPage page =
                SessionHistoryService.pageHistory(MAPPER, raw, SESSION_ID, null, null, "forward");

        assertEquals(List.of("[a, b]", "[c]"), texts(page.segments()));
        assertEquals(0, page.firstSegmentIndex());
        assertFalse(page.hasMore());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SessionHistoryService.pageHistory(
                        MAPPER, raw, SESSION_ID, SessionHistoryService.encodeCursor(MAPPER, SESSION_ID, 2, 3, 1),
                        null, "forward"));
        assertEquals("history cursor requires a limit", e.getMessage());
    }

    @Test
    @DisplayName("limit 边界：0 与负数拒绝，Integer.MAX_VALUE 不溢出")
    void limitBounds() {
        List<List<JsonNode>> raw = segments(seg("a", "b"));

        for (Integer limit : List.of(0, -1)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SessionHistoryService.pageHistory(MAPPER, raw, SESSION_ID, null, limit, "forward"));
            assertEquals("invalid history limit", e.getMessage());
        }

        SessionHistoryService.HistoryPage page = SessionHistoryService.pageHistory(
                MAPPER, raw, SESSION_ID, null, Integer.MAX_VALUE, "forward");
        assertEquals(List.of("[a, b]"), texts(page.segments()));
        assertFalse(page.hasMore());
    }

    @Test
    @DisplayName("direction 只接受 forward / backward")
    void invalidDirection() {
        List<List<JsonNode>> raw = segments(seg("a"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SessionHistoryService.pageHistory(MAPPER, raw, SESSION_ID, null, 1, "sideways"));
        assertEquals("invalid history direction", e.getMessage());
    }

    @Test
    @DisplayName("空会话：无选中切片，firstSegmentIndex 为 null")
    void emptySession() {
        List<List<JsonNode>> raw = segments(List.of(), List.of());

        SessionHistoryService.HistoryPage page =
                SessionHistoryService.pageHistory(MAPPER, raw, SESSION_ID, null, 2, "backward");

        assertEquals(List.of(), texts(page.segments()));
        assertNull(page.firstSegmentIndex());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        assertNull(page.previousCursor());
    }

    // ---- 游标编解码 ----

    @Test
    @DisplayName("游标往返：encode → decode 后边界即编码时的 offset")
    void cursorRoundTrip() {
        List<List<JsonNode>> raw = segments(seg("a", "b"), seg("c", "d", "e"));

        String cursor = SessionHistoryService.encodeCursor(MAPPER, SESSION_ID, 2, 5, 3);
        SessionHistoryService.HistoryPage page = SessionHistoryService.pageHistory(
                MAPPER, raw, SESSION_ID, cursor, 2, "forward");

        // 快照钉住 2 段 5 条消息，边界 3 → 取第 4 条起的两条
        assertEquals(List.of("[d, e]"), texts(page.segments()));
        assertFalse(page.hasMore());
        assertTrue(page.previousCursor() != null);
    }

    @Test
    @DisplayName("游标快照：段数/消息数超出现有存储报越界")
    void cursorSnapshotOutOfRange() {
        List<List<JsonNode>> raw = segments(seg("a", "b"), seg("c"));

        IllegalArgumentException tooManySegments = assertThrows(IllegalArgumentException.class,
                () -> SessionHistoryService.pageHistory(
                        MAPPER, raw, SESSION_ID, SessionHistoryService.encodeCursor(MAPPER, SESSION_ID, 3, 3, 0),
                        1, "forward"));
        assertEquals("history cursor snapshot is out of range", tooManySegments.getMessage());

        IllegalArgumentException tooManyMessages = assertThrows(IllegalArgumentException.class,
                () -> SessionHistoryService.pageHistory(
                        MAPPER, raw, SESSION_ID, SessionHistoryService.encodeCursor(MAPPER, SESSION_ID, 2, 4, 0),
                        1, "forward"));
        assertEquals("history cursor snapshot is out of range", tooManyMessages.getMessage());
    }

    @Test
    @DisplayName("游标快照：offset 超过去重后总数报越界")
    void cursorOffsetOutOfRange() {
        List<List<JsonNode>> raw = segments(seg("a", "b", "c"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SessionHistoryService.pageHistory(
                        MAPPER, raw, SESSION_ID, SessionHistoryService.encodeCursor(MAPPER, SESSION_ID, 1, 3, 4),
                        1, "forward"));
        assertEquals("history cursor offset is out of range", e.getMessage());
    }

    @Test
    @DisplayName("游标快照：后续追加不改变既有分页（前缀固定）")
    void cursorSnapshotIsPrefix() {
        // 游标生成时只有 1 段 2 条消息；现在存储长到 3 条，按旧游标翻页仍只看前缀
        List<List<JsonNode>> grown = segments(seg("a", "b"), seg("c"));

        SessionHistoryService.HistoryPage page = SessionHistoryService.pageHistory(
                MAPPER, grown, SESSION_ID,
                SessionHistoryService.encodeCursor(MAPPER, SESSION_ID, 1, 2, 2), 2, "forward");

        assertEquals(List.of(), texts(page.segments()));
        assertFalse(page.hasMore());
    }

    @Test
    @DisplayName("非法游标一律 invalid history cursor")
    void invalidCursors() {
        List<String> cursors = List.of(
                "!!!not-base64!!!",
                SessionHistoryService.encodeCursor(MAPPER, SESSION_ID, 1, 1, 0) + "$$",
                Base64.getUrlEncoder().withoutPadding().encodeToString("not-json".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(("[2,\"" + SESSION_ID + "\",1,1,0]").getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(("[1,\"other-session\",1,1,0]").getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(("[1,\"" + SESSION_ID + "\",1,1]").getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(("[1,\"" + SESSION_ID + "\",-1,1,0]").getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(("[1,\"" + SESSION_ID + "\",1.5,1,0]").getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(("[true,\"" + SESSION_ID + "\",1,1,0]").getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(("[1,\"" + SESSION_ID + "\",true,1,0]").getBytes(StandardCharsets.UTF_8)));
        for (String cursor : cursors) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SessionHistoryService.pageHistory(
                            MAPPER, segments(seg("a")), SESSION_ID, cursor, 1, "forward"),
                    "游标 " + cursor + " 应被拒绝");
            assertEquals("invalid history cursor", e.getMessage());
        }
    }

    @Test
    @DisplayName("游标编码为无 padding 的 base64url 紧凑 JSON")
    void cursorEncodingMatchesPython() {
        String cursor = SessionHistoryService.encodeCursor(MAPPER, SESSION_ID, 2, 5, 3);

        assertEquals("[1,\"" + SESSION_ID + "\",2,5,3]",
                new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        assertFalse(cursor.contains("="));
        assertFalse(cursor.contains("+"));
        assertFalse(cursor.contains("/"));
    }

    // ---- 应答组装 ----

    @Test
    @DisplayName("result 四键齐全，null 也序列化，segments 是消息 JSON 本身")
    void buildResultShape() {
        SessionHistoryService.HistoryPage page = SessionHistoryService.pageHistory(
                MAPPER, segments(List.of()), SESSION_ID, null, 2, "backward");

        JsonNode result = SessionHistoryService.buildResult(MAPPER, page);

        assertEquals("[]", result.get("segments").toString());
        assertTrue(result.get("firstSegmentIndex").isNull());
        assertTrue(result.get("nextCursor").isNull());
        assertTrue(result.get("previousCursor").isNull());
        assertFalse(result.get("hasMore").booleanValue());
        assertEquals(5, result.size());
    }

    @Test
    @DisplayName("result 保留消息 payload 原文")
    void buildResultKeepsPayload() {
        List<List<JsonNode>> raw = segments(new ArrayList<>());
        JsonNode message = MAPPER.readTree("{\"kind\":\"request\",\"parts\":[{\"content\":\"hi\"}]}");
        raw.get(0).add(message);

        JsonNode result = SessionHistoryService.buildResult(
                MAPPER, SessionHistoryService.pageHistory(MAPPER, raw, SESSION_ID, null, 5, "forward"));

        assertEquals(message, result.get("segments").get(0).get(0));
    }
}
