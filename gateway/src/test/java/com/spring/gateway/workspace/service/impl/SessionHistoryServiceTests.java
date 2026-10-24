package com.spring.gateway.workspace.service.impl;

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
 * 用内存行库伪造 RowFetcher 喂给静态分页方法。
 *
 * @author hanbing
 * @since 2026-10-03
 */
class SessionHistoryServiceTests {

    private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- 构造消息 payload ----

    private static JsonNode user(String text) {
        return MAPPER.readTree("{\"kind\":\"request\",\"parts\":[{\"part_kind\":\"user-prompt\",\"content\":"
                + MAPPER.writeValueAsString(text) + "}]}");
    }

    private static JsonNode ai(String text) {
        return MAPPER.readTree("{\"kind\":\"response\",\"parts\":[{\"part_kind\":\"text\",\"content\":"
                + MAPPER.writeValueAsString(text) + "}]}");
    }

    private static JsonNode toolCall(String id) {
        return MAPPER.readTree("{\"kind\":\"response\",\"parts\":[{\"part_kind\":\"tool-call\",\"tool_call_id\":"
                + MAPPER.writeValueAsString(id) + "}]}");
    }

    private static JsonNode toolRet(String id) {
        return MAPPER.readTree("{\"kind\":\"request\",\"parts\":[{\"part_kind\":\"tool-return\",\"tool_call_id\":"
                + MAPPER.writeValueAsString(id) + ",\"content\":\"ok\"}]}");
    }

    private static JsonNode inject(String text) {
        return MAPPER.readTree("{\"kind\":\"request\",\"metadata\":{\"source\":\"file_monitor\"},"
                + "\"parts\":[{\"part_kind\":\"user-prompt\",\"content\":"
                + MAPPER.writeValueAsString(text) + "}]}");
    }

    // ---- 内存行库：id 从 1 递增，fake RowFetcher 从中切片 ----

    private static final class FakeStore {
        private final List<SessionHistoryServiceImpl.Row> rows = new ArrayList<>();

        void add(int segmentNo, JsonNode payload) {
            rows.add(new SessionHistoryServiceImpl.Row(rows.size() + 1, segmentNo, payload));
        }

        /** 一个回合：提问 + 若干轮（AI 说一句 + 调工具 + 工具返回）+ 最终回答 */
        void addTurn(int segmentNo, String tag, int toolRounds) {
            add(segmentNo, user("问题" + tag));
            for (int i = 0; i < toolRounds; i++) {
                add(segmentNo, ai(tag + "第" + i + "句"));
                add(segmentNo, toolCall(tag + "-" + i));
                add(segmentNo, toolRet(tag + "-" + i));
            }
            add(segmentNo, ai("回答" + tag));
        }

        long maxId() {
            return rows.size();
        }

        SessionHistoryServiceImpl.RowFetcher fetcher() {
            return new SessionHistoryServiceImpl.RowFetcher() {
                @Override
                public List<SessionHistoryServiceImpl.Row> fetchBackward(long before, long anchor, int limit) {
                    List<SessionHistoryServiceImpl.Row> out = new ArrayList<>();
                    for (long id = Math.min(before - 1, anchor); id >= 1 && out.size() < limit; id--) {
                        out.add(rows.get((int) id - 1));
                    }
                    return out;
                }

                @Override
                public List<SessionHistoryServiceImpl.Row> fetchForward(long fromId, long anchor, int limit) {
                    List<SessionHistoryServiceImpl.Row> out = new ArrayList<>();
                    for (long id = fromId; id <= Math.min(anchor, rows.size()) && out.size() < limit; id++) {
                        if (id >= 1) {
                            out.add(rows.get((int) id - 1));
                        }
                    }
                    return out;
                }
            };
        }
    }

    private static SessionHistoryServiceImpl.HistoryPage backward(FakeStore store, String cursor, int limit) {
        long anchor;
        long boundary;
        if (cursor == null) {
            anchor = store.maxId();
            boundary = anchor + 1;
        } else {
            long[] pos = decode(cursor);
            anchor = pos[0];
            boundary = pos[1];
        }
        return SessionHistoryServiceImpl.backwardPage(store.fetcher(), MAPPER, SESSION_ID, anchor, boundary, limit);
    }

    private static long[] decode(String cursor) {
        try {
            JsonNode data = MAPPER.readTree(Base64.getUrlDecoder().decode(cursor));
            return new long[]{data.get(2).asLong(), data.get(3).asLong()};
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<JsonNode> flat(SessionHistoryServiceImpl.HistoryPage page) {
        List<JsonNode> out = new ArrayList<>();
        for (List<JsonNode> segment : page.segments()) {
            out.addAll(segment);
        }
        return out;
    }

    private static boolean isUser(JsonNode payload) {
        for (JsonNode part : payload.path("parts")) {
            if ("user-prompt".equals(part.path("part_kind").asText()) && part.path("content").isTextual()) {
                return true;
            }
        }
        return false;
    }

    private static String firstText(SessionHistoryServiceImpl.HistoryPage page) {
        JsonNode first = flat(page).getFirst();
        return first.path("parts").get(0).path("content").asText();
    }

    // ---- 跨段去重（limit=null 完整历史路径） ----

    @Test
    @DisplayName("跨段去重：剥掉与累计前缀重叠的尾部")
    void dedupStripsOverlap() {
        List<List<JsonNode>> result = SessionHistoryServiceImpl.dedupSegments(
                List.of(seg("a", "b", "c"), seg("b", "c", "d")));

        assertEquals(List.of("[a, b, c]", "[d]"), texts(result));
    }

    @Test
    @DisplayName("跨段去重：命中起始点取 s+k 最大者")
    void dedupTakesLargestOverlap() {
        List<List<JsonNode>> result = SessionHistoryServiceImpl.dedupSegments(
                List.of(seg("p", "q", "r"), seg("r", "p", "q", "r")));

        assertEquals(List.of("[p, q, r]", "[r]"), texts(result));
    }

    private static List<JsonNode> seg(String... texts) {
        List<JsonNode> segment = new ArrayList<>();
        for (String text : texts) {
            segment.add(new StringNode(text));
        }
        return segment;
    }

    private static List<String> texts(List<List<JsonNode>> segments) {
        return segments.stream()
                .map(segment -> segment.stream().map(JsonNode::asText).toList())
                .map(Object::toString)
                .toList();
    }

    // ---- backward 分页 ----

    @Test
    @DisplayName("backward：页首对齐到回合开头的用户消息")
    void backwardAlignsToTurnStart() {
        FakeStore store = new FakeStore();
        store.addTurn(0, "A", 1);
        store.addTurn(0, "B", 3);
        store.addTurn(0, "C", 1);

        // limit=4：从尾部数够 4 条可见（回答C、第0句C、问题C、回答B）后继续退到回合 B 的开头
        SessionHistoryServiceImpl.HistoryPage page = backward(store, null, 4);

        assertEquals("问题B", firstText(page));
        assertTrue(page.hasMore());
        assertNull(page.nextCursor());

        SessionHistoryServiceImpl.HistoryPage older = backward(store, page.previousCursor(), 4);
        assertEquals("问题A", firstText(older));
        assertFalse(older.hasMore());
        assertNull(older.previousCursor());
        // 更旧一页的 nextCursor 指回较新一页的起点
        assertEquals(flat(page).getFirst(), flat(backward(store, null, 4)).getFirst());
    }

    @Test
    @DisplayName("backward：数够 limit 后不退即停，而是再退到上一个回合开头")
    void backwardExtendsToPreviousTurnStart() {
        FakeStore store = new FakeStore();
        store.addTurn(0, "A", 0);
        store.addTurn(0, "B", 0);

        // limit=2 在回合 B 末尾已数够，但仍退到回合 A 的开头才切
        SessionHistoryServiceImpl.HistoryPage page = backward(store, null, 2);

        assertEquals(List.of(user("问题A"), ai("回答A"), user("问题B"), ai("回答B")), flat(page));
        assertFalse(page.hasMore());
    }

    @Test
    @DisplayName("backward：单轮可见消息超过硬上限时硬截断，页首可以不是用户消息")
    void backwardHardLimit() {
        FakeStore store = new FakeStore();
        store.add(0, user("巨回合"));
        for (int i = 0; i < 600; i++) {
            store.add(0, ai("第" + i + "句"));
        }

        SessionHistoryServiceImpl.HistoryPage page = backward(store, null, 30);

        assertEquals(500, flat(page).size());
        assertFalse(isUser(flat(page).getFirst()));
        assertTrue(page.hasMore());

        SessionHistoryServiceImpl.HistoryPage rest = backward(store, page.previousCursor(), 30);
        assertEquals("巨回合", firstText(rest));
        assertFalse(rest.hasMore());
    }

    @Test
    @DisplayName("backward：自动注入的消息不算可见条目、不作页首")
    void backwardSkipsAutoInjected() {
        FakeStore store = new FakeStore();
        store.addTurn(0, "A", 0);
        store.add(0, inject("文件变更通知"));
        store.addTurn(0, "B", 0);

        SessionHistoryServiceImpl.HistoryPage page = backward(store, null, 4);

        assertEquals("问题A", firstText(page));
        assertFalse(page.hasMore());
    }

    @Test
    @DisplayName("backward：跨段重叠在流式翻页中剥掉，多页拼接不重复不缺漏")
    void backwardCrossSegmentDedup() {
        FakeStore store = new FakeStore();
        store.add(0, user("问题A"));
        store.add(0, ai("回答A"));
        // 段 1 = 压缩基线：摘要（list content 的 user-prompt）+ 保留尾（回答A 的重复）+ 新内容
        store.add(1, MAPPER.readTree("{\"kind\":\"request\",\"parts\":[{\"part_kind\":\"user-prompt\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"摘要\",\"metadata\":"
                + "\"pydantic-ai-harness.compaction.receipt.v1\"}]}]}"));
        store.add(1, ai("回答A"));
        store.add(1, user("问题B"));
        store.add(1, ai("回答B"));

        List<JsonNode> joined = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            SessionHistoryServiceImpl.HistoryPage page = backward(store, cursor, 1);
            joined.addAll(0, flat(page));
            pages++;
            if (!page.hasMore()) {
                break;
            }
            cursor = page.previousCursor();
            assertTrue(pages < 20, "游标链不收敛");
        }

        // 摘要消息（content 为 list）不算用户消息，不作页首；重叠的「回答A」只出现一次
        long occurrences = joined.stream().filter(m -> m.equals(ai("回答A"))).count();
        assertEquals(1, occurrences);
        assertEquals(user("问题A"), joined.getFirst());
        assertEquals(ai("回答B"), joined.getLast());
    }

    @Test
    @DisplayName("backward：段号跳跃时补空段占位")
    void backwardFillsEmptySegments() {
        FakeStore store = new FakeStore();
        store.add(0, user("问题A"));
        store.add(0, ai("回答A"));
        store.add(2, user("问题B"));
        store.add(2, ai("回答B"));

        SessionHistoryServiceImpl.HistoryPage page = backward(store, null, 100);

        assertEquals(3, page.segments().size());
        assertEquals(List.of(), page.segments().get(1));
        assertEquals(0, page.firstSegmentIndex());
    }

    @Test
    @DisplayName("backward：快照锚点之后追加的消息不可见")
    void backwardAnchorPinsSnapshot() {
        FakeStore store = new FakeStore();
        store.addTurn(0, "A", 0);
        long anchor = store.maxId();
        store.addTurn(0, "B", 0);

        SessionHistoryServiceImpl.HistoryPage page = SessionHistoryServiceImpl.backwardPage(
                store.fetcher(), MAPPER, SESSION_ID, anchor, anchor + 1, 10);

        assertEquals(List.of(user("问题A"), ai("回答A")), flat(page));
        assertFalse(page.hasMore());
    }

    @Test
    @DisplayName("backward：空会话返回空页")
    void backwardEmpty() {
        FakeStore store = new FakeStore();

        SessionHistoryServiceImpl.HistoryPage page = SessionHistoryServiceImpl.backwardPage(
                store.fetcher(), MAPPER, SESSION_ID, -1, 0, 10);

        assertEquals(List.of(), page.segments());
        assertNull(page.firstSegmentIndex());
        assertFalse(page.hasMore());
        assertNull(page.previousCursor());
        assertNull(page.nextCursor());
    }

    // ---- forward 分页 ----

    @Test
    @DisplayName("forward：按原始消息数取页，nextCursor 接力到尾")
    void forwardPaging() {
        FakeStore store = new FakeStore();
        store.addTurn(0, "A", 1);
        store.addTurn(0, "B", 0);

        SessionHistoryServiceImpl.HistoryPage first = SessionHistoryServiceImpl.forwardPage(
                store.fetcher(), MAPPER, SESSION_ID, store.maxId(), 0, 3);
        assertEquals(3, flat(first).size());
        assertTrue(first.hasMore());
        assertNull(first.previousCursor());

        SessionHistoryServiceImpl.HistoryPage second = SessionHistoryServiceImpl.forwardPage(
                store.fetcher(), MAPPER, SESSION_ID, store.maxId(), decode(first.nextCursor())[1], 3);
        assertEquals(List.of(toolRet("A-0"), ai("回答A"), user("问题B")), flat(second));
        assertTrue(second.hasMore());

        SessionHistoryServiceImpl.HistoryPage third = SessionHistoryServiceImpl.forwardPage(
                store.fetcher(), MAPPER, SESSION_ID, store.maxId(), decode(second.nextCursor())[1], 3);
        assertEquals(List.of(ai("回答B")), flat(third));
        assertFalse(third.hasMore());
        assertNull(third.nextCursor());
    }

    // ---- 游标编解码 ----

    @Test
    @DisplayName("游标编码为无 padding 的 base64url 紧凑 JSON，版本号 2")
    void cursorEncodingShape() {
        String cursor = SessionHistoryServiceImpl.encodeCursor(MAPPER, SESSION_ID, 42, 7);

        assertEquals("[2,\"" + SESSION_ID + "\",42,7]",
                new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        assertFalse(cursor.contains("="));
        assertFalse(cursor.contains("+"));
        assertFalse(cursor.contains("/"));
    }

    // ---- 应答组装 ----

    @Test
    @DisplayName("result 四键齐全，null 也序列化，segments 是消息 JSON 本身")
    void buildResultShape() {
        SessionHistoryServiceImpl.HistoryPage page = SessionHistoryServiceImpl.backwardPage(
                new FakeStore().fetcher(), MAPPER, SESSION_ID, -1, 0, 10);

        JsonNode result = SessionHistoryServiceImpl.buildResult(MAPPER, page);

        assertEquals("[]", result.get("segments").toString());
        assertTrue(result.get("firstSegmentIndex").isNull());
        assertTrue(result.get("nextCursor").isNull());
        assertTrue(result.get("previousCursor").isNull());
        assertFalse(result.get("hasMore").booleanValue());
        assertEquals(5, result.size());
    }
}
