package com.spring.gateway.workspace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.workspace.dto.InternalBranchResult;
import com.spring.gateway.workspace.entity.Message;
import com.spring.gateway.workspace.entity.MessageNode;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.workspace.mapper.MessageMapper;
import com.spring.gateway.workspace.mapper.MessageNodeMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 会话消息分支。
 *
 * @author hanbing
 * @since 2026-11-27
 */
@Service
public class MessageBranchService {
    public static final String ACTION_NORMAL = "normal";
    public static final String ACTION_EDIT = "edit";
    public static final String ACTION_RETRY = "retry";
    public static final String LEGACY_PREFIX = "legacy:";
    public static final int MAX_ACTION_LIMIT = 6;

    static final String INTERNAL_ROW_ID = "_springAgentRowId";
    static final String INTERNAL_NODE_ID = "_springAgentNodeId";
    private static final Set<String> AUTO_SOURCES = Set.of("file_monitor");

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final MessageNodeMapper nodeMapper;
    private final SystemSettingService settings;
    private final ObjectMapper objectMapper;

    public MessageBranchService(SessionMapper sessionMapper, MessageMapper messageMapper,
                                MessageNodeMapper nodeMapper, SystemSettingService settings,
                                ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.nodeMapper = nodeMapper;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验编辑或重试资格。
     *
     * @param sessionId 会话 ID
     * @param userId 用户 ID
     * @param action 操作类型
     * @param branchId 目标节点 ID
     */
    @Transactional(readOnly = true)
    public void validateAction(String sessionId, Long userId, String action, String branchId) {
        Session session = ownedSession(sessionId, userId, false);
        Target target = resolveTarget(session, branchId);
        checkLimit(sessionId, target, action);
        if (ACTION_RETRY.equals(action)) {
            promptOf(target);
        }
    }

    /**
     * 新建回答变体并返回实际输入。
     *
     * @param sessionId 会话 ID
     * @param userId 用户 ID
     * @param action 操作类型
     * @param branchId 目标节点 ID
     * @param input 新输入
     * @return 新节点与实际输入
     */
    @Transactional
    public InternalBranchResult prepare(String sessionId, Long userId, String action,
                                        String branchId, String input) {
        Session session = ownedSession(sessionId, userId, true);
        if (ACTION_NORMAL.equals(action)) {
            MessageNode active = activeNode(session);
            if (branchId != null && !branchId.isBlank()) {
                Target selected = resolveTarget(session, branchId);
                if (selected.originalPending()) {
                    insertNode(selected.node());
                }
                active = selected.node();
            }
            MessageNode node = newNode(session, active, UUID.randomUUID().toString(), ACTION_NORMAL);
            if (active == null) {
                node.setSegmentNo(session.getCurrentSegment());
                Message latest = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, sessionId)
                        .orderByDesc(Message::getId)
                        .last("LIMIT 1"));
                node.setLegacyAnchorId(latest == null ? 0L : latest.getId());
                node.setLegacyEndId(node.getLegacyAnchorId());
            } else {
                node.setSegmentNo(active.getSegmentNo());
            }
            node.setVersion(1);
            insertNode(node);
            activate(session, node);
            return new InternalBranchResult(node.getId(), input);
        }
        if (!ACTION_EDIT.equals(action) && !ACTION_RETRY.equals(action)) {
            throw new BizException(400, "不支持的消息操作");
        }
        if (ACTION_EDIT.equals(action) && (input == null || input.isBlank() || input.length() > 64000)) {
            throw new BizException(400, input != null && input.length() > 64000
                    ? "输入内容过长（最多 64000 字符）" : "消息内容不能为空");
        }
        Target target = resolveTarget(session, branchId);
        checkLimit(sessionId, target, action);
        String effectiveInput = ACTION_RETRY.equals(action) ? promptOf(target) : input;
        if (target.originalPending()) {
            insertNode(target.node());
        }
        MessageNode parent = target.parent();
        String groupId = ACTION_EDIT.equals(action)
                ? target.groupId() : UUID.randomUUID().toString();
        MessageNode node = newNode(session, parent, groupId, action);
        if (ACTION_EDIT.equals(action)) {
            node.setAnswerGroupId(node.getId());
        } else {
            node.setAnswerGroupId(target.node().getAnswerGroupId() == null
                    ? target.node().getGroupId() : target.node().getAnswerGroupId());
        }
        node.setSegmentNo(parent == null ? target.node().getSegmentNo() : parent.getSegmentNo());
        if (parent == null) {
            node.setLegacyAnchorId(target.node().getLegacyAnchorId());
            node.setLegacyEndId(target.node().getLegacyAnchorId());
            node.setLegacyTargetId(target.node().getLegacyTargetId());
        }
        node.setVersion(ACTION_EDIT.equals(action)
                ? nodeMapper.maxVersion(sessionId, target.groupId()) + 1 : 1);
        insertNode(node);
        activate(session, node);
        return new InternalBranchResult(node.getId(), effectiveInput);
    }

    /**
     * 切换当前回答变体。
     *
     * @param sessionId 会话 ID
     * @param userId 用户 ID
     * @param branchId 目标节点 ID
     */
    @Transactional
    public void select(String sessionId, Long userId, String branchId) {
        Session session = ownedSession(sessionId, userId, true);
        List<MessageNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<MessageNode>()
                .eq(MessageNode::getSessionId, sessionId));
        MessageNode selected = nodes.stream().filter(node -> node.getId().equals(branchId)).findFirst()
                .orElseThrow(() -> new BizException(404, "消息版本不存在"));
        Set<String> visited = new java.util.HashSet<>();
        visited.add(selected.getId());
        while (true) {
            String parentId = selected.getId();
            Optional<MessageNode> child = nodes.stream()
                    .filter(node -> parentId.equals(node.getParentId()))
                    .max(Comparator.comparing(MessageNode::getCreatedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(MessageNode::getId));
            if (child.isEmpty() || !visited.add(child.get().getId())) {
                break;
            }
            selected = child.get();
        }
        session.setActiveNodeId(selected.getId());
        sessionMapper.updateById(session);
    }

    /**
     * 查询当前分支上下文。
     *
     * @param session 会话行
     * @return 当前分支上下文
     */
    public BranchContext context(Session session) {
        if (session.getActiveNodeId() == null) {
            return BranchContext.legacy(session.getCurrentSegment());
        }
        List<MessageNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<MessageNode>()
                .eq(MessageNode::getSessionId, session.getId()));
        Map<String, MessageNode> byId = new HashMap<>();
        for (MessageNode node : nodes) {
            byId.put(node.getId(), node);
        }
        List<MessageNode> path = new ArrayList<>();
        MessageNode current = byId.get(session.getActiveNodeId());
        while (current != null && path.size() <= nodes.size()) {
            path.add(current);
            current = current.getParentId() == null ? null : byId.get(current.getParentId());
        }
        if (path.isEmpty() || path.getLast().getParentId() != null) {
            throw new BizException(500, "消息分支数据无效");
        }
        path = path.reversed();
        MessageNode root = path.getFirst();
        long legacyCutoff = MessageNode.KIND_LEGACY.equals(root.getKind())
                ? valueOrZero(root.getLegacyEndId()) : valueOrZero(root.getLegacyAnchorId());
        return new BranchContext(true, path, path.getLast().getSegmentNo(), legacyCutoff, nodes);
    }

    /**
     * 查询当前分支完整消息。
     *
     * @param session 会话行
     * @return 按段号和主键排序的消息
     */
    public List<Message> allMessages(Session session) {
        BranchContext context = context(session);
        if (!context.branched()) {
            return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getSessionId, session.getId())
                    .orderByAsc(Message::getSegmentNo)
                    .orderByAsc(Message::getId));
        }
        return messageMapper.selectList(visibleQuery(context)
                .orderByAsc(Message::getSegmentNo)
                .orderByAsc(Message::getId));
    }

    /** 查询发给模型的当前压缩段。 */
    public List<Message> modelMessages(Session session) {
        BranchContext context = context(session);
        if (!context.branched()) {
            return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getSessionId, session.getId())
                    .eq(Message::getSegmentNo, session.getCurrentSegment())
                    .orderByAsc(Message::getId));
        }
        return messageMapper.selectList(visibleQuery(context)
                .eq(Message::getSegmentNo, context.segmentNo())
                .orderByAsc(Message::getId));
    }

    /** 当前分支的最新消息主键。 */
    public Long latestId(Session session) {
        BranchContext context = context(session);
        if (!context.branched()) {
            return messageMapper.selectLatestId(session.getId());
        }
        List<Message> rows = messageMapper.selectList(visibleQuery(context)
                .orderByDesc(Message::getId).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.getFirst().getId();
    }

    /** 当前分支的倒序消息页。 */
    public List<Message> backward(BranchContext context, String sessionId, long before, long anchor, int limit) {
        if (!context.branched()) {
            return messageMapper.selectHistoryBackward(sessionId, before, anchor, limit);
        }
        return messageMapper.selectList(visibleQuery(context)
                .le(Message::getId, anchor)
                .lt(Message::getId, before)
                .orderByDesc(Message::getId)
                .last("LIMIT " + limit));
    }

    /** 当前分支的正序消息页。 */
    public List<Message> forward(BranchContext context, String sessionId, long fromId, long anchor, int limit) {
        if (!context.branched()) {
            return messageMapper.selectHistoryForward(sessionId, fromId, anchor, limit);
        }
        return messageMapper.selectList(visibleQuery(context)
                .le(Message::getId, anchor)
                .ge(Message::getId, fromId)
                .orderByAsc(Message::getId)
                .last("LIMIT " + limit));
    }

    /** 给历史消息添加分支元数据。 */
    public void decorateHistory(ObjectNode result, BranchContext context) {
        Map<String, MessageNode> byId = new HashMap<>();
        Map<String, List<MessageNode>> groups = new HashMap<>();
        for (MessageNode node : context.nodes()) {
            byId.put(node.getId(), node);
            groups.computeIfAbsent(node.getGroupId(), ignored -> new ArrayList<>()).add(node);
        }
        groups.values().forEach(group -> group.sort(Comparator.comparing(MessageNode::getVersion)));
        MessageNode legacyRoot = context.branched()
                && MessageNode.KIND_LEGACY.equals(context.path().getFirst().getKind())
                ? context.path().getFirst() : null;
        String legacyBranch = null;
        for (JsonNode segment : result.path("segments")) {
            for (JsonNode message : segment) {
                if (!(message instanceof ObjectNode payload)) {
                    continue;
                }
                ObjectNode metadata = payload.path("metadata").isObject()
                        ? (ObjectNode) payload.path("metadata") : payload.putObject("metadata");
                long rowId = metadata.path(INTERNAL_ROW_ID).asLong(0L);
                String nodeId = metadata.path(INTERNAL_NODE_ID).asText("");
                metadata.remove(List.of(INTERNAL_ROW_ID, INTERNAL_NODE_ID));
                MessageNode node = nodeId.isEmpty() ? null : byId.get(nodeId);
                if (node == null && legacyRoot != null
                        && rowId > valueOrZero(legacyRoot.getLegacyAnchorId())
                        && rowId <= valueOrZero(legacyRoot.getLegacyEndId())) {
                    node = legacyRoot;
                }
                if (node == null && isUserPrompt(payload) && rowId > 0) {
                    legacyBranch = LEGACY_PREFIX + rowId;
                } else if (node != null) {
                    legacyBranch = null;
                }
                if (node != null) {
                    metadata.set("springAgent", branchMetadata(node, groups.get(node.getGroupId())));
                } else if (legacyBranch != null) {
                    metadata.set("springAgent", legacyMetadata(legacyBranch));
                }
            }
        }
    }

    /** 写入分页前的内部行标记。 */
    public JsonNode historyPayload(Message item) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(item.getPayload());
        } catch (Exception e) {
            throw new IllegalArgumentException("message payload is not valid json");
        }
        if (parsed instanceof ObjectNode payload) {
            ObjectNode metadata = payload.path("metadata").isObject()
                    ? (ObjectNode) payload.path("metadata") : payload.putObject("metadata");
            long sourceId = item.getNodeId() == null && item.getLegacyId() != null
                    ? item.getLegacyId() : item.getId();
            metadata.put(INTERNAL_ROW_ID, sourceId);
            metadata.put(INTERNAL_NODE_ID, item.getNodeId() == null ? "" : item.getNodeId());
        }
        return parsed;
    }

    /** 当前生效的编辑次数上限。 */
    public int editLimit() {
        return boundedLimit(settings.getLong(com.spring.gateway.settings.entity.SystemSetting.KEY_MESSAGE_EDIT_LIMIT, 6L));
    }

    /** 当前生效的重试次数上限。 */
    public int retryLimit() {
        return boundedLimit(settings.getLong(com.spring.gateway.settings.entity.SystemSetting.KEY_ANSWER_RETRY_LIMIT, 6L));
    }

    private static int boundedLimit(long value) {
        return (int) Math.max(0, Math.min(MAX_ACTION_LIMIT, value));
    }

    private LambdaQueryWrapper<Message> visibleQuery(BranchContext context) {
        List<String> nodeIds = context.path().stream().map(MessageNode::getId).toList();
        return new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, context.path().getFirst().getSessionId())
                .and(wrapper -> wrapper
                        .and(nodes -> nodes.in(Message::getNodeId, nodeIds)
                                .le(Message::getSegmentNo, context.segmentNo()))
                        .or(legacy -> legacy.isNull(Message::getNodeId)
                                .apply("COALESCE(legacy_id, id) <= {0}", context.legacyCutoff())));
    }

    private Session ownedSession(String sessionId, Long userId, boolean lock) {
        LambdaQueryWrapper<Session> query = new LambdaQueryWrapper<Session>()
                .eq(Session::getId, sessionId)
                .eq(Session::getUserId, userId)
                .eq(Session::getStatus, Session.STATUS_ACTIVE);
        Session row = sessionMapper.selectOne(lock ? query.last("FOR UPDATE") : query);
        if (row == null) {
            throw new BizException(404, "会话不存在");
        }
        return row;
    }

    private MessageNode activeNode(Session session) {
        if (session.getActiveNodeId() == null) {
            return null;
        }
        MessageNode node = nodeMapper.selectOne(new LambdaQueryWrapper<MessageNode>()
                .eq(MessageNode::getId, session.getActiveNodeId())
                .eq(MessageNode::getSessionId, session.getId()));
        if (node == null) {
            throw new BizException(500, "当前消息分支无效");
        }
        return node;
    }

    private Target resolveTarget(Session session, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            throw new BizException(400, "缺少消息版本 ID");
        }
        if (!branchId.startsWith(LEGACY_PREFIX)) {
            MessageNode node = nodeMapper.selectOne(new LambdaQueryWrapper<MessageNode>()
                    .eq(MessageNode::getId, branchId)
                    .eq(MessageNode::getSessionId, session.getId()));
            if (node == null) {
                throw new BizException(404, "消息版本不存在");
            }
            MessageNode parent = node.getParentId() == null ? null : nodeMapper.selectOne(
                    new LambdaQueryWrapper<MessageNode>()
                            .eq(MessageNode::getId, node.getParentId())
                            .eq(MessageNode::getSessionId, session.getId()));
            Message legacyMessage = MessageNode.KIND_LEGACY.equals(node.getKind())
                    ? messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                            .eq(Message::getId, node.getLegacyTargetId())
                            .eq(Message::getSessionId, session.getId())) : null;
            return new Target(node, parent, node.getGroupId(), legacyMessage, false);
        }
        long messageId;
        try {
            messageId = Long.parseLong(branchId.substring(LEGACY_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new BizException(400, "消息版本 ID 无效");
        }
        Message message = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getId, messageId)
                .eq(Message::getSessionId, session.getId())
                .isNull(Message::getNodeId));
        if (message == null || !isUserPrompt(readPayload(message))) {
            throw new BizException(404, "消息版本不存在");
        }
        MessageNode original = nodeMapper.selectOne(new LambdaQueryWrapper<MessageNode>()
                .eq(MessageNode::getSessionId, session.getId())
                .eq(MessageNode::getLegacyTargetId, messageId)
                .eq(MessageNode::getKind, MessageNode.KIND_LEGACY));
        if (original != null) {
            return new Target(original, null, original.getGroupId(), message, false);
        }
        original = legacyNode(session, message);
        return new Target(original, null, original.getGroupId(), message, true);
    }

    private MessageNode legacyNode(Session session, Message target) {
        MessageNode original = newNode(session, null, UUID.randomUUID().toString(), MessageNode.KIND_LEGACY);
        original.setVersion(1);
        original.setSegmentNo(target.getSegmentNo());
        original.setLegacyAnchorId(target.getId() - 1);
        original.setLegacyTargetId(target.getId());
        original.setCreatedAt(target.getCreatedAt());
        List<Message> later = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, session.getId())
                .isNull(Message::getNodeId)
                .gt(Message::getId, target.getId())
                .orderByAsc(Message::getId));
        long end = target.getId();
        for (Message row : later) {
            if (!target.getSegmentNo().equals(row.getSegmentNo()) || isUserPrompt(readPayload(row))) {
                break;
            }
            end = row.getId();
        }
        original.setLegacyEndId(end);
        return original;
    }

    private MessageNode newNode(Session session, MessageNode parent, String groupId, String kind) {
        MessageNode node = new MessageNode();
        node.setId(UUID.randomUUID().toString());
        node.setSessionId(session.getId());
        node.setParentId(parent == null ? null : parent.getId());
        node.setGroupId(groupId);
        node.setAnswerGroupId(groupId);
        node.setKind(kind);
        node.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return node;
    }

    private void insertNode(MessageNode node) {
        nodeMapper.insert(node);
    }

    private void activate(Session session, MessageNode node) {
        session.setActiveNodeId(node.getId());
        session.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        sessionMapper.updateById(session);
    }

    private void checkLimit(String sessionId, Target target, String action) {
        int limit = ACTION_EDIT.equals(action) ? editLimit() : retryLimit();
        LambdaQueryWrapper<MessageNode> query = new LambdaQueryWrapper<MessageNode>()
                .eq(MessageNode::getSessionId, sessionId)
                .eq(MessageNode::getKind, action);
        if (ACTION_EDIT.equals(action)) {
            query.eq(MessageNode::getGroupId, target.groupId());
        } else {
            String answerGroupId = target.node().getAnswerGroupId() == null
                    ? target.node().getGroupId() : target.node().getAnswerGroupId();
            query.eq(MessageNode::getAnswerGroupId, answerGroupId);
        }
        long used = nodeMapper.selectCount(query);
        if (used >= limit) {
            String label = ACTION_EDIT.equals(action) ? "修改" : "重新生成";
            throw new BizException(400, label + "次数已达上限（" + limit + " 次）");
        }
    }

    private String promptOf(Target target) {
        if (target.legacyMessage() != null) {
            return userText(readPayload(target.legacyMessage()));
        }
        List<Message> rows = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, target.node().getSessionId())
                .eq(Message::getNodeId, target.node().getId())
                .orderByAsc(Message::getSegmentNo)
                .orderByAsc(Message::getId));
        for (Message row : rows) {
            JsonNode payload = readPayload(row);
            if (isUserPrompt(payload)) {
                return userText(payload);
            }
        }
        throw new BizException(400, "找不到需要重新生成的问题");
    }

    private String userText(JsonNode payload) {
        for (JsonNode part : payload.path("parts")) {
            if ("user-prompt".equals(part.path("part_kind").asText()) && part.path("content").isTextual()) {
                return part.path("content").asText();
            }
        }
        throw new BizException(400, "消息内容不支持重新生成");
    }

    private JsonNode readPayload(Message message) {
        try {
            return objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            throw new BizException(500, "消息内容解析失败");
        }
    }

    private ObjectNode branchMetadata(MessageNode node, List<MessageNode> group) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("branchId", node.getId());
        value.put("groupId", node.getGroupId());
        value.put("kind", node.getKind());
        value.put("version", node.getVersion());
        value.put("versionCount", group.size());
        value.put("editCount", group.stream().filter(item -> ACTION_EDIT.equals(item.getKind())).count());
        value.put("retryCount", group.stream().filter(item -> ACTION_RETRY.equals(item.getKind())).count());
        int index = group.indexOf(node);
        value.put("previousBranchId", index > 0 ? group.get(index - 1).getId() : null);
        value.put("nextBranchId", index + 1 < group.size() ? group.get(index + 1).getId() : null);
        return value;
    }

    private ObjectNode legacyMetadata(String branchId) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("branchId", branchId);
        value.put("kind", "normal");
        value.put("version", 1);
        value.put("versionCount", 1);
        value.put("editCount", 0);
        value.put("retryCount", 0);
        return value;
    }

    static boolean isUserPrompt(JsonNode payload) {
        String source = payload.path("metadata").path("source").asText("");
        if (!"request".equals(payload.path("kind").asText()) || AUTO_SOURCES.contains(source)) {
            return false;
        }
        for (JsonNode part : payload.path("parts")) {
            if ("user-prompt".equals(part.path("part_kind").asText()) && part.path("content").isTextual()) {
                return true;
            }
        }
        return false;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    /** 当前分支与旧消息边界。 */
    public record BranchContext(boolean branched, List<MessageNode> path, int segmentNo,
                                long legacyCutoff, List<MessageNode> nodes) {
        static BranchContext legacy(int segmentNo) {
            return new BranchContext(false, List.of(), segmentNo, 0L, List.of());
        }
    }

    private record Target(MessageNode node, MessageNode parent, String groupId, Message legacyMessage,
                          boolean originalPending) {
    }
}
