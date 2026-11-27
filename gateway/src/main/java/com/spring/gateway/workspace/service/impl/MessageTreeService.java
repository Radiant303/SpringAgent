package com.spring.gateway.workspace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.workspace.entity.Message;
import com.spring.gateway.workspace.entity.MessageNode;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.workspace.mapper.MessageMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 会话完整消息树。
 *
 * @author hanbing
 * @since 2026-11-27
 */
@Service
public class MessageTreeService {
    private final MessageMapper messageMapper;
    private final MessageBranchService messageBranchService;
    private final ObjectMapper objectMapper;

    public MessageTreeService(MessageMapper messageMapper, MessageBranchService messageBranchService,
                              ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.messageBranchService = messageBranchService;
        this.objectMapper = objectMapper;
    }

    /** 把完整节点树写入历史响应。 */
    public void append(ObjectNode result, Session session, MessageBranchService.BranchContext context) {
        List<Message> rows = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, session.getId())
                .orderByAsc(Message::getSegmentNo)
                .orderByAsc(Message::getId));
        Map<String, List<Message>> rowsByNode = new HashMap<>();
        List<Message> legacyRows = new ArrayList<>();
        for (Message row : rows) {
            if (row.getNodeId() == null) {
                legacyRows.add(row);
            } else {
                rowsByNode.computeIfAbsent(row.getNodeId(), ignored -> new ArrayList<>()).add(row);
            }
        }

        List<Builder> builders = new ArrayList<>();
        Map<String, Builder> byId = new HashMap<>();
        Map<Long, Builder> legacyOriginals = new HashMap<>();
        for (MessageNode node : context.nodes()) {
            Builder builder = new Builder(node, dedupMessages(rowsByNode.getOrDefault(node.getId(), List.of())));
            builders.add(builder);
            byId.put(builder.branchId, builder);
            if (node.getLegacyTargetId() != null && MessageNode.KIND_LEGACY.equals(node.getKind())) {
                legacyOriginals.put(node.getLegacyTargetId(), builder);
            }
        }

        List<Builder> legacyPath = new ArrayList<>();
        Builder previous = null;
        for (LegacyTurn turn : legacyTurns(legacyRows)) {
            Builder builder = legacyOriginals.get(turn.targetId);
            if (builder != null) {
                builder.messages = turn.messages;
            } else {
                builder = Builder.legacy(turn);
                builders.add(builder);
                byId.put(builder.branchId, builder);
            }
            if (previous != null) {
                builder.parentId = previous.branchId;
            }
            legacyPath.add(builder);
            previous = builder;
        }
        for (Builder builder : builders) {
            if (builder.node != null && builder.parentId == null && builder.node.getLegacyAnchorId() != null) {
                Builder parent = legacyParent(legacyPath, builder.node.getLegacyAnchorId());
                if (parent != null && parent != builder) {
                    builder.parentId = parent.branchId;
                }
            }
        }
        writeBuilders(result, context, builders);
    }

    private List<JsonNode> dedupRawMessages(List<Message> rows) {
        Map<Integer, List<JsonNode>> segments = new TreeMap<>();
        for (Message row : rows) {
            segments.computeIfAbsent(row.getSegmentNo(), ignored -> new ArrayList<>())
                    .add(messageBranchService.historyPayload(row));
        }
        List<JsonNode> result = new ArrayList<>();
        for (List<JsonNode> segment : SessionHistoryServiceImpl.dedupSegments(new ArrayList<>(segments.values()))) {
            result.addAll(segment);
        }
        return result;
    }

    private List<JsonNode> dedupMessages(List<Message> rows) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode message : dedupRawMessages(rows)) {
            result.add(clean(message));
        }
        return result;
    }

    private List<LegacyTurn> legacyTurns(List<Message> rows) {
        Map<Long, LocalDateTime> createdAt = new HashMap<>();
        for (Message row : rows) {
            createdAt.putIfAbsent(row.getId(), row.getCreatedAt());
        }
        List<LegacyTurn> turns = new ArrayList<>();
        LegacyTurn current = null;
        for (JsonNode message : dedupRawMessages(rows)) {
            long rowId = message.path("metadata").path(MessageBranchService.INTERNAL_ROW_ID).asLong(0L);
            if (MessageBranchService.isUserPrompt(message) && rowId > 0) {
                current = new LegacyTurn(rowId, createdAt.get(rowId));
                turns.add(current);
            }
            if (current != null) {
                current.messages.add(clean(message));
            }
        }
        return turns;
    }

    private static JsonNode clean(JsonNode message) {
        if (message instanceof ObjectNode payload && payload.path("metadata").isObject()) {
            ((ObjectNode) payload.path("metadata")).remove(List.of(
                    MessageBranchService.INTERNAL_ROW_ID, MessageBranchService.INTERNAL_NODE_ID));
        }
        return message;
    }

    private static Builder legacyParent(List<Builder> path, long anchor) {
        Builder result = null;
        for (Builder builder : path) {
            if (builder.legacyTargetId != null && builder.legacyTargetId <= anchor) {
                result = builder;
            }
        }
        return result;
    }

    private void writeBuilders(ObjectNode result, MessageBranchService.BranchContext context,
                               List<Builder> builders) {
        Map<String, List<Builder>> messageGroups = new LinkedHashMap<>();
        Map<String, List<Builder>> answerGroups = new LinkedHashMap<>();
        for (Builder builder : builders) {
            if (!MessageNode.KIND_RETRY.equals(builder.kind)) {
                messageGroups.computeIfAbsent(builder.groupId, ignored -> new ArrayList<>()).add(builder);
            }
            answerGroups.computeIfAbsent(builder.answerGroupId, ignored -> new ArrayList<>()).add(builder);
        }
        messageGroups.values().forEach(MessageTreeService::sortMessageGroup);
        answerGroups.values().forEach(MessageTreeService::sortAnswerGroup);
        ArrayNode nodes = result.putArray("nodes");
        for (Builder builder : builders) {
            List<Builder> messages = messageGroups.getOrDefault(builder.groupId, List.of());
            if (messages.isEmpty()) {
                messages = List.of(builder);
            }
            List<Builder> answers = answerGroups.getOrDefault(builder.answerGroupId, List.of(builder));
            int messageIndex = messages.indexOf(builder);
            int answerIndex = answers.indexOf(builder);
            ObjectNode node = nodes.addObject();
            node.put("branchId", builder.branchId);
            node.put("parentId", builder.parentId);
            node.put("groupId", builder.groupId);
            node.put("answerGroupId", builder.answerGroupId);
            node.put("kind", builder.kind);
            node.put("version", messageIndex >= 0 ? messages.get(messageIndex).version : builder.version);
            node.put("versionCount", messages.size());
            node.put("previousBranchId", messageIndex > 0 ? messages.get(messageIndex - 1).branchId : null);
            node.put("nextBranchId", messageIndex >= 0 && messageIndex + 1 < messages.size()
                    ? messages.get(messageIndex + 1).branchId : null);
            node.put("editCount", messages.stream().filter(item -> MessageNode.KIND_EDIT.equals(item.kind)).count());
            node.put("answerVersion", answerIndex + 1);
            node.put("answerVersionCount", answers.size());
            node.put("previousAnswerBranchId", answerIndex > 0 ? answers.get(answerIndex - 1).branchId : null);
            node.put("nextAnswerBranchId", answerIndex >= 0 && answerIndex + 1 < answers.size()
                    ? answers.get(answerIndex + 1).branchId : null);
            node.put("retryCount", answers.stream().filter(item -> MessageNode.KIND_RETRY.equals(item.kind)).count());
            node.put("createdAt", builder.createdAt == null ? null : TimeFormat.isoUtc(builder.createdAt));
            ArrayNode contents = node.putArray("messages");
            for (JsonNode message : builder.messages) {
                contents.add(message);
            }
        }
        result.put("activeNodeId", context.branched() ? context.path().getLast().getId() : null);
    }

    private static void sortMessageGroup(List<Builder> group) {
        group.sort(Comparator.comparingInt((Builder builder) -> builder.version)
                .thenComparing(builder -> builder.createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(builder -> builder.branchId));
    }

    private static void sortAnswerGroup(List<Builder> group) {
        group.sort(Comparator.comparing((Builder builder) -> builder.createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(builder -> builder.branchId));
    }

    private static final class Builder {
        private final String branchId;
        private final MessageNode node;
        private String parentId;
        private final String groupId;
        private final String answerGroupId;
        private final String kind;
        private final int version;
        private final LocalDateTime createdAt;
        private final Long legacyTargetId;
        private List<JsonNode> messages;

        private Builder(MessageNode node, List<JsonNode> messages) {
            this.branchId = node.getId();
            this.node = node;
            this.parentId = node.getParentId();
            this.groupId = node.getGroupId();
            this.answerGroupId = node.getAnswerGroupId() == null ? node.getGroupId() : node.getAnswerGroupId();
            this.kind = node.getKind();
            this.version = node.getVersion();
            this.createdAt = node.getCreatedAt();
            this.legacyTargetId = node.getLegacyTargetId();
            this.messages = messages;
        }

        private Builder(LegacyTurn turn) {
            this.branchId = MessageBranchService.LEGACY_PREFIX + turn.targetId;
            this.node = null;
            this.groupId = this.branchId;
            this.answerGroupId = this.branchId;
            this.kind = MessageNode.KIND_NORMAL;
            this.version = 1;
            this.createdAt = turn.createdAt;
            this.legacyTargetId = turn.targetId;
            this.messages = turn.messages;
        }

        private static Builder legacy(LegacyTurn turn) {
            return new Builder(turn);
        }
    }

    private static final class LegacyTurn {
        private final long targetId;
        private final LocalDateTime createdAt;
        private final List<JsonNode> messages = new ArrayList<>();

        private LegacyTurn(long targetId, LocalDateTime createdAt) {
            this.targetId = targetId;
            this.createdAt = createdAt;
        }
    }
}
