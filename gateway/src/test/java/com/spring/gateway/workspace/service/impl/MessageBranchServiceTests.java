package com.spring.gateway.workspace.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.workspace.dto.InternalBranchResult;
import com.spring.gateway.workspace.entity.Message;
import com.spring.gateway.workspace.entity.MessageNode;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.workspace.mapper.MessageMapper;
import com.spring.gateway.workspace.mapper.MessageNodeMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息分支业务测试。
 *
 * @author hanbing
 * @since 2026-11-27
 */
class MessageBranchServiceTests {
    private final SessionMapper sessions = mock(SessionMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final MessageNodeMapper nodes = mock(MessageNodeMapper.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MessageBranchService service = new MessageBranchService(sessions, messages, nodes, settings, json);
    private Session session;

    @BeforeEach
    void setup() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> type : List.of(Session.class, Message.class, MessageNode.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, type.getSimpleName()), type);
        }
        session = new Session();
        session.setId("s1");
        session.setUserId(7L);
        session.setCurrentSegment(3);
        session.setStatus(Session.STATUS_ACTIVE);
        when(sessions.selectOne(any())).thenReturn(session);
        when(settings.getLong(anyString(), eq(6L))).thenAnswer(call ->
                SystemSetting.KEY_MESSAGE_EDIT_LIMIT.equals(call.getArgument(0)) ? 6L : 6L);
    }

    @Test
    void normalTurnCreatesRootWithLegacyPrefix() {
        Message latest = message(20L, 2, null, user("old"));
        when(messages.selectOne(any())).thenReturn(latest);

        InternalBranchResult result = service.prepare("s1", 7L, "normal", null, "new");

        ArgumentCaptor<MessageNode> inserted = ArgumentCaptor.forClass(MessageNode.class);
        verify(nodes).insert(inserted.capture());
        MessageNode node = inserted.getValue();
        assertEquals("new", result.input());
        assertEquals(node.getId(), result.branchId());
        assertEquals(MessageNode.KIND_NORMAL, node.getKind());
        assertEquals(1, node.getVersion());
        assertEquals(3, node.getSegmentNo());
        assertEquals(20L, node.getLegacyAnchorId());
        assertEquals(node.getId(), session.getActiveNodeId());
        assertNull(node.getParentId());
    }

    @Test
    void editCreatesSiblingWithoutChangingInput() {
        MessageNode parent = node("parent", null, "other", "normal", 1);
        MessageNode target = node("target", "parent", "group", "normal", 1);
        target.setSegmentNo(2);
        when(nodes.selectOne(any())).thenReturn(target, parent);
        when(nodes.maxVersion("s1", "group")).thenReturn(2);
        when(nodes.selectCount(any())).thenReturn(1L);

        InternalBranchResult result = service.prepare("s1", 7L, "edit", "target", "edited");

        ArgumentCaptor<MessageNode> inserted = ArgumentCaptor.forClass(MessageNode.class);
        verify(nodes).insert(inserted.capture());
        assertEquals("edited", result.input());
        assertEquals("parent", inserted.getValue().getParentId());
        assertEquals("group", inserted.getValue().getGroupId());
        assertEquals("edit", inserted.getValue().getKind());
        assertEquals(3, inserted.getValue().getVersion());
        assertEquals(parent.getSegmentNo(), inserted.getValue().getSegmentNo());
    }

    @Test
    void retryUsesOriginalQuestionInsteadOfEmptyInput() {
        MessageNode target = node("target", null, "group", "normal", 1);
        target.setSegmentNo(2);
        when(nodes.selectOne(any())).thenReturn(target);
        when(messages.selectList(any())).thenReturn(List.of(message(11L, 2, "target", user("original"))));

        InternalBranchResult result = service.prepare("s1", 7L, "retry", "target", "");

        assertEquals("original", result.input());
        ArgumentCaptor<MessageNode> inserted = ArgumentCaptor.forClass(MessageNode.class);
        verify(nodes).insert(inserted.capture());
        assertEquals("retry", inserted.getValue().getKind());
        assertEquals(2, inserted.getValue().getSegmentNo());
    }

    @Test
    void firstLegacyRetryCreatesOriginalAndVariant() {
        Message legacy = message(10L, 1, null, user("legacy question"));
        legacy.setCreatedAt(java.time.LocalDateTime.now());
        when(messages.selectOne(any())).thenReturn(legacy);
        when(messages.selectList(any())).thenReturn(List.of());
        when(nodes.selectOne(any())).thenReturn(null);
        when(nodes.maxVersion(anyString(), anyString())).thenReturn(1);

        InternalBranchResult result = service.prepare("s1", 7L, "retry", "legacy:10", "");

        assertEquals("legacy question", result.input());
        ArgumentCaptor<MessageNode> inserted = ArgumentCaptor.forClass(MessageNode.class);
        verify(nodes, org.mockito.Mockito.times(2)).insert(inserted.capture());
        MessageNode original = inserted.getAllValues().getFirst();
        MessageNode retry = inserted.getAllValues().getLast();
        assertEquals("legacy", original.getKind());
        assertEquals(10L, original.getLegacyTargetId());
        assertEquals(9L, original.getLegacyAnchorId());
        assertEquals(10L, original.getLegacyEndId());
        assertEquals(original.getGroupId(), retry.getGroupId());
        assertEquals(2, retry.getVersion());
        assertEquals(9L, retry.getLegacyAnchorId());
        assertEquals(retry.getId(), session.getActiveNodeId());
    }

    @Test
    void zeroLimitRejectsBeforeWriting() {
        MessageNode target = node("target", null, "group", "normal", 1);
        when(nodes.selectOne(any())).thenReturn(target);
        when(settings.getLong(SystemSetting.KEY_MESSAGE_EDIT_LIMIT, 6L)).thenReturn(0L);

        BizException error = assertThrows(BizException.class,
                () -> service.validateAction("s1", 7L, "edit", "target"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("0"));
        verify(nodes, never()).insert(any(MessageNode.class));
    }

    @Test
    void metadataExposesVersionNavigationAndCounts() {
        MessageNode original = node("original", null, "group", "normal", 1);
        MessageNode retry = node("retry", null, "group", "retry", 2);
        Message item = message(12L, 0, "retry", ai("answer"));
        ObjectNode result = json.createObjectNode();
        ArrayNode segments = result.putArray("segments");
        ArrayNode segment = segments.addArray();
        segment.add(service.historyPayload(item));
        MessageBranchService.BranchContext context = new MessageBranchService.BranchContext(
                true, List.of(retry), 0, 0L, List.of(original, retry));

        service.decorateHistory(result, context);

        JsonNode metadata = result.path("segments").get(0).get(0).path("metadata");
        JsonNode branch = metadata.path("springAgent");
        assertEquals("retry", branch.path("branchId").asText());
        assertEquals(2, branch.path("version").asInt());
        assertEquals(2, branch.path("versionCount").asInt());
        assertEquals(1, branch.path("retryCount").asInt());
        assertEquals("original", branch.path("previousBranchId").asText());
        assertFalse(metadata.has("_springAgentRowId"));
        assertFalse(metadata.has("_springAgentNodeId"));
    }

    @Test
    void compactedLegacyMessageKeepsOriginalSourceId() {
        Message copy = message(99L, 4, null, user("old"));
        copy.setLegacyId(10L);

        JsonNode payload = service.historyPayload(copy);

        assertEquals(10L, payload.path("metadata").path("_springAgentRowId").asLong());
    }

    @Test
    void selectingVersionRestoresLatestDescendant() {
        MessageNode target = node("target", null, "group", "normal", 1);
        MessageNode older = node("older", "target", "g2", "normal", 1);
        MessageNode newer = node("newer", "target", "g3", "normal", 1);
        target.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        older.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 2, 0, 0));
        newer.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 3, 0, 0));
        when(nodes.selectList(any())).thenReturn(List.of(target, older, newer));

        service.select("s1", 7L, "target");

        assertEquals("newer", session.getActiveNodeId());
        verify(sessions).updateById(session);
    }

    @Test
    void appendPreservesLegacySourceAndStripsInternalMarkers() {
        MessageNode active = node("active", null, "group", "normal", 1);
        session.setActiveNodeId(active.getId());
        when(nodes.selectOne(any())).thenReturn(active);
        InternalStoreServiceImpl store = new InternalStoreServiceImpl(
                sessions, messages, mock(com.spring.gateway.workspace.service.WorkService.class), json, service, nodes);
        JsonNode legacy = json.readTree("{\"kind\":\"request\",\"metadata\":{\"_springAgentStored\":true,"
                + "\"_springAgentNodeId\":\"\",\"_springAgentLegacyId\":10},"
                + "\"parts\":[{\"part_kind\":\"user-prompt\",\"content\":\"old\"}]}");

        store.appendMessages("s1", true, null, List.of(legacy, ai("new")));

        ArgumentCaptor<Collection<Message>> written = ArgumentCaptor.forClass(Collection.class);
        verify(messages).insert(written.capture(), eq(500));
        List<Message> rows = List.copyOf(written.getValue());
        assertNull(rows.getFirst().getNodeId());
        assertEquals(10L, rows.getFirst().getLegacyId());
        assertEquals("active", rows.getLast().getNodeId());
        assertNull(rows.getLast().getLegacyId());
        JsonNode savedLegacy = json.readTree(rows.getFirst().getPayload());
        assertFalse(savedLegacy.path("metadata").has("_springAgentStored"));
        assertFalse(savedLegacy.path("metadata").has("_springAgentLegacyId"));
        assertEquals(4, session.getCurrentSegment());
        assertEquals(4, active.getSegmentNo());
    }

    private MessageNode node(String id, String parent, String group, String kind, int version) {
        MessageNode node = new MessageNode();
        node.setId(id);
        node.setSessionId("s1");
        node.setParentId(parent);
        node.setGroupId(group);
        node.setKind(kind);
        node.setVersion(version);
        node.setSegmentNo(1);
        return node;
    }

    private Message message(Long id, int segment, String nodeId, JsonNode payload) {
        Message message = new Message();
        message.setId(id);
        message.setSessionId("s1");
        message.setSegmentNo(segment);
        message.setNodeId(nodeId);
        message.setPayload(json.writeValueAsString(payload));
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
