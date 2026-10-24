package com.spring.gateway.workspace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.UnknownSessionException;
import com.spring.gateway.workspace.entity.Message;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.workspace.mapper.MessageMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.workspace.service.SessionHistoryService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 会话历史分页。
 *
 * @author hanbing
 * @since 2026-10-03
 */
@Service
public class SessionHistoryServiceImpl implements SessionHistoryService {

    /** 游标载荷版本：结构不兼容升级时 +1，旧游标按 invalid 拒绝 */
    private static final int CURSOR_VERSION = 2;

    /** JSON-RPC 错误码：会话不存在 */
    public static final int SESSION_NOT_FOUND = -32002;

    /** 单页可见消息硬上限：一轮对话超过这个量级时放弃页首对齐，硬截断 */
    private static final int PAGE_ITEM_HARD_LIMIT = 500;

    /** 倒序批量取数的批大小 */
    private static final int BATCH_SIZE = 200;

    /** 段头非重叠前缀的扫描上限（压缩摘要等新消息） */
    private static final int HEAD_SKIP = 3;

    /** 段重叠去重的扫描上限：实际重叠是压缩保留的尾部，正常不超过二十余条 */
    private static final int STRIP_SCAN = 64;

    /** 系统自动注入的消息来源：不算用户消息 */
    private static final Set<String> AUTO_SOURCES = Set.of("file_monitor");

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public SessionHistoryServiceImpl(SessionMapper sessionMapper, MessageMapper messageMapper,
                                     ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 归属校验后分页查询会话历史
     *
     * @param sessionId 会话 ID
     * @param userId    当前用户 ID
     * @param cursor    分页游标，可为 null（null 从对应方向的端点开始）
     * @param limit     每页可见消息数，null 表示完整历史（此时不接受 cursor）
     * @param direction 翻页方向：forward / backward
     * @return JSON-RPC result 节点：segments / firstSegmentIndex / nextCursor / previousCursor / hasMore
     * @throws UnknownSessionException    会话不存在、不属于该用户或已删除（-32002）
     * @throws IllegalArgumentException 参数或游标非法（-32602）
     */
    public ObjectNode queryHistory(String sessionId, Long userId, String cursor, Integer limit, String direction) {
        Session row = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getId, sessionId)
                .eq(Session::getUserId, userId)
                .eq(Session::getStatus, Session.STATUS_ACTIVE));
        if (row == null) {
            throw new UnknownSessionException(sessionId);
        }
        if (!"forward".equals(direction) && !"backward".equals(direction)) {
            throw new IllegalArgumentException("invalid history direction");
        }
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("invalid history limit");
        }
        if (cursor != null && limit == null) {
            throw new IllegalArgumentException("history cursor requires a limit");
        }
        if (limit == null) {
            List<Message> rows = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getSessionId, sessionId)
                    .orderByAsc(Message::getSegmentNo)
                    .orderByAsc(Message::getId));
            List<List<JsonNode>> segments = dedupSegments(loadSegments(row, rows));
            int total = count(segments);
            return buildResult(objectMapper,
                    new HistoryPage(total == 0 ? List.of() : segments, total == 0 ? null : 0, null, null, false));
        }
        long anchor;
        long boundary;
        if (cursor == null) {
            anchor = latestId(sessionId);
            boundary = "backward".equals(direction) ? anchor + 1 : 0;
        } else {
            CursorPosition position = decodeCursor(objectMapper, cursor, sessionId);
            anchor = position.anchorId();
            boundary = position.boundaryId();
            if (boundary > anchor + 1) {
                throw new IllegalArgumentException("history cursor offset is out of range");
            }
        }
        RowFetcher fetcher = rowFetcher(sessionId);
        HistoryPage page = "backward".equals(direction)
                ? backwardPage(fetcher, objectMapper, sessionId, anchor, boundary, limit)
                : forwardPage(fetcher, objectMapper, sessionId, anchor, boundary, limit);
        return buildResult(objectMapper, page);
    }

    /** 把 messages 行按 (segment_no, id) 装入段列表：段数 = current_segment + 1（含空段） */
    private List<List<JsonNode>> loadSegments(Session row, List<Message> rows) {
        int currentSegment = row.getCurrentSegment() == null ? 0 : row.getCurrentSegment();
        List<List<JsonNode>> segments = new ArrayList<>(currentSegment + 1);
        for (int i = 0; i <= currentSegment; i++) {
            segments.add(new ArrayList<>());
        }
        for (Message item : rows) {
            int segmentNo = item.getSegmentNo() == null ? -1 : item.getSegmentNo();
            if (segmentNo < 0 || segmentNo >= segments.size()) {
                // 数据越界（库被绕过写入时）：跳过而不是越界崩溃
                continue;
            }
            segments.get(segmentNo).add(readPayload(objectMapper, item.getPayload()));
        }
        return segments;
    }

    private static JsonNode readPayload(ObjectMapper objectMapper, String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("message payload is not valid json");
        }
    }

    // ---- 分页核心：流式批量取数，不依赖 DB 连接的纯逻辑 ----

    /**
     * backward 一页：从边界向旧数够 limit 条可见消息后，退到最近的回合开头。
     * 单轮对话可见消息触达硬上限时放弃对齐、硬截断。
     */
    static HistoryPage backwardPage(RowFetcher fetcher, ObjectMapper objectMapper, String sessionId,
                                    long anchor, long boundary, int limit) {
        BackwardStream stream = new BackwardStream(fetcher, anchor, boundary);
        List<Row> collected = new ArrayList<>();
        int count = 0;
        Row row;
        while ((row = stream.next()) != null) {
            if (count >= limit && isUserPrompt(row.payload())) {
                collected.add(row);
                break;
            }
            if (count >= PAGE_ITEM_HARD_LIMIT) {
                break;
            }
            collected.add(row);
            if (isVisibleItem(row.payload())) {
                count++;
            }
        }
        if (collected.isEmpty()) {
            return new HistoryPage(List.of(), null, null, null, false);
        }
        List<Row> asc = collected.reversed();
        long oldestId = asc.getFirst().id();
        long newestId = asc.getLast().id();
        boolean hasMore = !fetcher.fetchBackward(oldestId, anchor, 1).isEmpty();
        String previousCursor = hasMore ? encodeCursor(objectMapper, sessionId, anchor, oldestId) : null;
        String nextCursor = newestId < anchor ? encodeCursor(objectMapper, sessionId, anchor, newestId + 1) : null;
        return new HistoryPage(groupSegments(asc), asc.getFirst().segmentNo(), nextCursor, previousCursor, hasMore);
    }

    /** forward 一页：从边界向新按原始消息数取 limit 条，不做可见计数与跨段去重 */
    static HistoryPage forwardPage(RowFetcher fetcher, ObjectMapper objectMapper, String sessionId,
                                   long anchor, long boundary, int limit) {
        List<Row> asc = new ArrayList<>();
        long from = boundary;
        while (asc.size() < limit) {
            List<Row> batch = fetcher.fetchForward(from, anchor, Math.min(BATCH_SIZE, limit - asc.size()));
            if (batch.isEmpty()) {
                break;
            }
            asc.addAll(batch);
            from = batch.getLast().id() + 1;
        }
        if (asc.isEmpty()) {
            return new HistoryPage(List.of(), null, null, null, false);
        }
        long newestId = asc.getLast().id();
        boolean hasMore = !fetcher.fetchForward(newestId + 1, anchor, 1).isEmpty();
        String nextCursor = hasMore ? encodeCursor(objectMapper, sessionId, anchor, newestId + 1) : null;
        String previousCursor = !fetcher.fetchBackward(boundary, anchor, 1).isEmpty()
                ? encodeCursor(objectMapper, sessionId, anchor, boundary) : null;
        return new HistoryPage(groupSegments(asc), asc.getFirst().segmentNo(), nextCursor, previousCursor, hasMore);
    }

    /** 按段号把升序行分组；段号跳跃时补空段占位，保证段下标与段号一致 */
    private static List<List<JsonNode>> groupSegments(List<Row> asc) {
        List<List<JsonNode>> segments = new ArrayList<>();
        int prevSeg = -1;
        for (Row row : asc) {
            if (segments.isEmpty() || row.segmentNo() != prevSeg) {
                if (!segments.isEmpty()) {
                    for (int gap = prevSeg + 1; gap < row.segmentNo(); gap++) {
                        segments.add(List.of());
                    }
                }
                segments.add(new ArrayList<>());
                prevSeg = row.segmentNo();
            }
            segments.getLast().add(row.payload());
        }
        return segments;
    }

    /** 前端可见的用户消息：非自动注入且含文本 prompt */
    private static boolean isUserPrompt(JsonNode payload) {
        if (!"request".equals(payload.path("kind").asText()) || isAutoInjected(payload)) {
            return false;
        }
        for (JsonNode part : payload.path("parts")) {
            if ("user-prompt".equals(part.path("part_kind").asText()) && part.path("content").isTextual()) {
                return true;
            }
        }
        return false;
    }

    /** 分页计数单位：用户消息或含正文的 AI 回复；工具调用与返回不占名额 */
    private static boolean isVisibleItem(JsonNode payload) {
        if (isUserPrompt(payload)) {
            return true;
        }
        if (!"response".equals(payload.path("kind").asText())) {
            return false;
        }
        for (JsonNode part : payload.path("parts")) {
            if ("text".equals(part.path("part_kind").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAutoInjected(JsonNode payload) {
        JsonNode source = payload.path("metadata").path("source");
        return source.isTextual() && AUTO_SOURCES.contains(source.asText());
    }

    /**
     * 倒序消息流：批量取数，跨段时剥掉新段与旧段尾部重叠的段头。
     * 段头扫描区以外的消息即时放行，区内的等到跨段结算或流耗尽才放出。
     */
    private static final class BackwardStream {
        private final RowFetcher fetcher;
        private final long anchor;
        private long before;
        private final ArrayDeque<Row> buf = new ArrayDeque<>();
        private final ArrayDeque<Row> hold = new ArrayDeque<>();
        private final ArrayDeque<Row> ready = new ArrayDeque<>();
        private int currentSeg = -1;
        private boolean eof;

        BackwardStream(RowFetcher fetcher, long anchor, long before) {
            this.fetcher = fetcher;
            this.anchor = anchor;
            this.before = before;
        }

        /** 流下一条（更旧的）消息；耗尽返回 null */
        Row next() {
            while (true) {
                if (!ready.isEmpty()) {
                    return ready.pollFirst();
                }
                if (hold.size() > HEAD_SKIP + STRIP_SCAN) {
                    return hold.pollFirst();
                }
                Row candidate = peekRow();
                if (candidate == null) {
                    if (hold.isEmpty()) {
                        return null;
                    }
                    ready.addAll(hold);
                    hold.clear();
                    continue;
                }
                int seg = candidate.segmentNo();
                if (currentSeg == -1 || seg == currentSeg) {
                    currentSeg = seg;
                    hold.addLast(poll());
                    continue;
                }
                if (seg > currentSeg) {
                    // 段号倒挂（库被绕过写入时）：跳过而不是死循环
                    poll();
                    continue;
                }
                ready.addAll(settleHold());
                hold.clear();
                currentSeg = seg;
                hold.addLast(poll());
            }
        }

        /** 结算当前段的段头扫描区：剥掉与旧段尾部重叠的前缀，放行保留部分 */
        private List<Row> settleHold() {
            List<Row> headAscRows = new ArrayList<>(hold).reversed();
            List<JsonNode> headAsc = headAscRows.stream().map(Row::payload).toList();
            List<JsonNode> tailAsc = peekAhead(STRIP_SCAN).reversed().stream().map(Row::payload).toList();
            int stripLo = 0;
            int stripHi = 0;
            for (int s = 0; s <= Math.min(HEAD_SKIP, headAsc.size()); s++) {
                for (int k = Math.min(tailAsc.size(), headAsc.size() - s); k > 0; k--) {
                    if (overlaps(tailAsc, k, headAsc, s, k)) {
                        if (s + k > stripHi) {
                            stripLo = s;
                            stripHi = s + k;
                        }
                        break;
                    }
                }
            }
            List<Row> kept = new ArrayList<>(headAscRows.subList(0, stripLo));
            kept.addAll(headAscRows.subList(stripHi, headAscRows.size()));
            return kept.reversed();
        }

        /** 查看流中接下来的最多 n 行（不消费），不足时补取批次 */
        private List<Row> peekAhead(int n) {
            while (buf.size() < n && !eof) {
                fetchBatch();
            }
            List<Row> all = new ArrayList<>(buf);
            return all.subList(0, Math.min(n, all.size()));
        }

        private Row poll() {
            Row head = peekRow();
            return head == null ? null : buf.pollFirst();
        }

        private Row peekRow() {
            if (buf.isEmpty() && !eof) {
                fetchBatch();
            }
            return buf.peekFirst();
        }

        private void fetchBatch() {
            List<Row> rows = fetcher.fetchBackward(before, anchor, BATCH_SIZE);
            if (rows.size() < BATCH_SIZE) {
                eof = true;
            }
            if (!rows.isEmpty()) {
                before = rows.getLast().id();
                buf.addAll(rows);
            }
        }
    }

    /** 消息行取数器：按 id 范围与方向批量取，payload 已解析 */
    interface RowFetcher {
        /** id &lt; before 且 id &lt;= anchor 的最近 limit 条，按 id 降序 */
        List<Row> fetchBackward(long before, long anchor, int limit);

        /** id &gt;= fromId 且 id &lt;= anchor 的最早 limit 条，按 id 升序 */
        List<Row> fetchForward(long fromId, long anchor, int limit);
    }

    /** 一条消息行：主键、段号、解析后的 payload */
    record Row(long id, int segmentNo, JsonNode payload) {
    }

    /**
     * 跨段去重：压缩改写后的新基线与旧段尾部有重叠前缀时，剥掉重叠部分。
     * 按 payload 的 JsonNode 深相等逐条比较。
     */
    static List<List<JsonNode>> dedupSegments(List<List<JsonNode>> segments) {
        List<List<JsonNode>> result = new ArrayList<>();
        List<JsonNode> acc = new ArrayList<>();
        for (List<JsonNode> seg : segments) {
            int stripLo = 0;
            int stripHi = 0;
            for (int s = 0; s <= Math.min(3, seg.size()); s++) {
                for (int k = Math.min(acc.size(), seg.size() - s); k > 0; k--) {
                    if (overlaps(acc, k, seg, s, k)) {
                        if (s + k > stripHi) {
                            stripLo = s;
                            stripHi = s + k;
                        }
                        break;
                    }
                }
            }
            List<JsonNode> deduped = new ArrayList<>(seg.subList(0, stripLo));
            deduped.addAll(seg.subList(stripHi, seg.size()));
            result.add(deduped);
            acc.addAll(deduped);
        }
        return result;
    }

    /** acc 末尾 k 个元素与 seg 从 segStart 起 k 个元素是否逐条深相等（JsonNode.equals 即结构深相等） */
    private static boolean overlaps(List<? extends Object> acc, int tailSize, List<? extends Object> seg,
                                    int segStart, int k) {
        for (int i = 0; i < k; i++) {
            Object left = acc.get(acc.size() - tailSize + i);
            Object right = seg.get(segStart + i);
            if (!left.equals(right)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把分页边界编码进游标：base64url（无 padding）的紧凑 JSON
     * {@code [2, sessionId, anchorId, boundaryId]}。anchorId 钉住快照（之后追加的 id 更大、
     * 不可见）；boundaryId 是分页边界：backward 取 id &lt; boundaryId，forward 取 id &gt;= boundaryId。
     */
    static String encodeCursor(ObjectMapper objectMapper, String sessionId, long anchorId, long boundaryId) {
        String payload = objectMapper.writeValueAsString(
                List.of(CURSOR_VERSION, sessionId, anchorId, boundaryId));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码并校验游标：版本号与会话 ID 不匹配、下标非负性不满足都按 invalid 拒绝 */
    private static CursorPosition decodeCursor(ObjectMapper objectMapper, String cursor, String sessionId) {
        JsonNode data;
        try {
            data = objectMapper.readTree(Base64.getUrlDecoder().decode(cursor));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid history cursor");
        }
        if (!data.isArray() || data.size() != 4) {
            throw new IllegalArgumentException("invalid history cursor");
        }
        JsonNode versionNode = data.get(0);
        if (!versionNode.isInt() || versionNode.asInt() != CURSOR_VERSION) {
            throw new IllegalArgumentException("invalid history cursor");
        }
        JsonNode sessionIdNode = data.get(1);
        if (!sessionIdNode.isTextual() || !sessionId.equals(sessionIdNode.asText())) {
            throw new IllegalArgumentException("invalid history cursor");
        }
        // 要求整型（拒绝 bool 与 float）且非负
        long[] values = new long[2];
        for (int i = 0; i < values.length; i++) {
            JsonNode node = data.get(2 + i);
            if (!node.isIntegralNumber() || !node.canConvertToExactIntegral() || node.asLong() < 0) {
                throw new IllegalArgumentException("invalid history cursor");
            }
            values[i] = node.asLong();
        }
        return new CursorPosition(values[0], values[1]);
    }

    private long latestId(String sessionId) {
        Message latest = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .orderByDesc(Message::getId)
                .last("LIMIT 1"));
        return latest == null ? -1L : latest.getId();
    }

    private RowFetcher rowFetcher(String sessionId) {
        return new RowFetcher() {
            @Override
            public List<Row> fetchBackward(long before, long anchor, int limit) {
                return toRows(messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, sessionId)
                        .le(Message::getId, anchor)
                        .lt(Message::getId, before)
                        .orderByDesc(Message::getId)
                        .last("LIMIT " + limit)));
            }

            @Override
            public List<Row> fetchForward(long fromId, long anchor, int limit) {
                return toRows(messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, sessionId)
                        .le(Message::getId, anchor)
                        .ge(Message::getId, fromId)
                        .orderByAsc(Message::getId)
                        .last("LIMIT " + limit)));
            }
        };
    }

    private List<Row> toRows(List<Message> messages) {
        List<Row> rows = new ArrayList<>(messages.size());
        for (Message item : messages) {
            rows.add(new Row(item.getId(), item.getSegmentNo(), readPayload(objectMapper, item.getPayload())));
        }
        return rows;
    }

    private static int count(List<List<JsonNode>> segments) {
        int total = 0;
        for (List<JsonNode> segment : segments) {
            total += segment.size();
        }
        return total;
    }

    /** 组装 JSON-RPC result；四个键始终存在（null 也序列化） */
    static ObjectNode buildResult(ObjectMapper objectMapper, HistoryPage page) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode segments = result.putArray("segments");
        for (List<JsonNode> segment : page.segments()) {
            ArrayNode item = segments.addArray();
            for (JsonNode message : segment) {
                item.add(message);
            }
        }
        result.put("firstSegmentIndex", page.firstSegmentIndex());
        result.put("nextCursor", page.nextCursor());
        result.put("previousCursor", page.previousCursor());
        result.put("hasMore", page.hasMore());
        return result;
    }

    /**
     * 一页历史快照。
     *
     * @param segments           选中段的消息切片（段内为追加序，段间可能隔空段占位）
     * @param firstSegmentIndex 选中第一个非空切片的段下标，无则为 null
     * @param nextCursor         向前续取的游标，无更多则 null
     * @param previousCursor     向后续取的游标，无更早则 null
     * @param hasMore            对应请求方向是否还有更多
     */
    record HistoryPage(List<List<JsonNode>> segments, Integer firstSegmentIndex,
                       String nextCursor, String previousCursor, boolean hasMore) {
    }

    /**
     * 游标里的快照：锚点主键（分页可见的最大 id）与边界主键。
     *
     * @param anchorId   快照锚点：首次查询时的最大消息 id
     * @param boundaryId 分页边界
     */
    private record CursorPosition(long anchorId, long boundaryId) {
    }
}
