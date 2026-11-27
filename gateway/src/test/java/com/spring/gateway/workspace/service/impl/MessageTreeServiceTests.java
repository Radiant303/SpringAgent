package com.spring.gateway.workspace.service.impl;

import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.workspace.entity.Message;
import com.spring.gateway.workspace.entity.MessageNode;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.workspace.mapper.MessageMapper;
import com.spring.gateway.workspace.mapper.MessageNodeMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 完整消息树测试。
 *
 * @author hanbing
 * @since 2026-11-27
 */
class MessageTreeServiceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final MessageMapper messages = mock(MessageMapper.class);
    private final MessageBranchService branches = new MessageBranchService(
            mock(SessionMapper.class), messages, mock(MessageNodeMapper.class),
            mock(SystemSettingService.class), json);
    private final MessageTreeService service = new MessageTreeService(messages, branches, json);

    @Test
    void editAndRetryUseIndependentVersionGroups() {
        MessageNode a = node("A", "ga", "ga", "normal", 1, 1);
        MessageNode a2 = node("A2", "ga2", "ga", "retry", 1, 2);
        MessageNode b = node("B", "ga", "gb", "edit", 2, 3);
        MessageNode b2 = node("B2", "gb2", "gb", "retry", 1, 4);
        List<Message> rows = List.of(
                message(1, "A", user("message A")), message(2, "A", ai("answer A1")),
                message(3, "A2", user("message A")), message(4, "A2", ai("answer A2")),
                message(5, "B", user("message B")), message(6, "B", ai("answer B1")),
                message(7, "B2", user("message B")), message(8, "B2", ai("answer B2")));
        when(messages.selectList(any())).thenReturn(rows);
        Session session = session();
        MessageBranchService.BranchContext context = new MessageBranchService.BranchContext(
                true, List.of(b2), 0, 0L, List.of(a, a2, b, b2));
        ObjectNode result = json.createObjectNode();

        service.append(result, session, context);

        JsonNode aView = node(result, "A");
        assertEquals(2, aView.path("versionCount").asInt());
        assertEquals(2, aView.path("answerVersionCount").asInt());
        assertEquals(1, aView.path("retryCount").asInt());
        assertEquals(1, aView.path("editCount").asInt());
        assertEquals("B", aView.path("nextBranchId").asText());
        assertEquals("A2", aView.path("nextAnswerBranchId").asText());
        JsonNode a2View = node(result, "A2");
        assertEquals(1, a2View.path("versionCount").asInt());
        assertEquals(2, a2View.path("answerVersion").asInt());
        JsonNode bView = node(result, "B");
        assertEquals(2, bView.path("version").asInt());
        assertEquals(2, bView.path("answerVersionCount").asInt());
        assertEquals(1, bView.path("retryCount").asInt());
        JsonNode b2View = node(result, "B2");
        assertEquals(2, b2View.path("answerVersion").asInt());
        assertEquals(2, b2View.path("answerVersionCount").asInt());
        assertEquals(1, b2View.path("retryCount").asInt());
        assertEquals("B", b2View.path("previousAnswerBranchId").asText());
        assertEquals("B2", result.path("activeNodeId").asText());
    }

    @Test
    void legacyMessagesBecomeLinkedTreeNodes() {
        when(messages.selectList(any())).thenReturn(List.of(
                message(1, null, user("A")), message(2, null, ai("A1")),
                message(3, null, user("B")), message(4, null, ai("B1"))));
        ObjectNode result = json.createObjectNode();

        service.append(result, session(), MessageBranchService.BranchContext.legacy(0));

        assertEquals(2, result.path("nodes").size());
        JsonNode first = result.path("nodes").get(0);
        JsonNode second = result.path("nodes").get(1);
        assertEquals("legacy:1", first.path("branchId").asText());
        assertEquals("legacy:3", second.path("branchId").asText());
        assertEquals("legacy:1", second.path("parentId").asText());
        assertEquals(2, first.path("messages").size());
        assertTrue(result.path("activeNodeId").isNull());
    }

    private JsonNode node(ObjectNode result, String id) {
        for (JsonNode node : result.path("nodes")) {
            if (id.equals(node.path("branchId").asText())) {
                return node;
            }
        }
        throw new AssertionError("missing node " + id);
    }

    private Session session() {
        Session session = new Session();
        session.setId("s1");
        session.setUserId(7L);
        session.setCurrentSegment(0);
        session.setStatus(Session.STATUS_ACTIVE);
        return session;
    }

    private MessageNode node(String id, String group, String answerGroup,
                             String kind, int version, int minute) {
        MessageNode node = new MessageNode();
        node.setId(id);
        node.setSessionId("s1");
        node.setGroupId(group);
        node.setAnswerGroupId(answerGroup);
        node.setKind(kind);
        node.setVersion(version);
        node.setSegmentNo(0);
        node.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, minute));
        return node;
    }

    private Message message(long id, String nodeId, JsonNode payload) {
        Message message = new Message();
        message.setId(id);
        message.setSessionId("s1");
        message.setNodeId(nodeId);
        message.setSegmentNo(0);
        message.setPayload(json.writeValueAsString(payload));
        message.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return message;
    }

    private JsonNode user(String text) {
        return json.readTree("{\"kind\":\"request\",\"parts\":[{\"part_kind\":\"user-prompt\",\"content\":"
                + json.writeValueAsString(text) + "}]}");
    }

    private JsonNode ai(String text) {
        return json.readTree("{\"kind\":\"response\",\"parts\":[{\"part_kind\":\"text\",\"content\":"
                + json.writeValueAsString(text) + "}]}");
    }
}
