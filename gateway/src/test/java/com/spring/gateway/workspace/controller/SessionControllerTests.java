package com.spring.gateway.workspace.controller;

import com.spring.gateway.identity.service.AuthSessionService;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.engine.client.EngineClient;
import com.spring.gateway.workspace.entity.Message;
import com.spring.gateway.workspace.entity.MessageNode;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.workspace.entity.Work;
import com.spring.gateway.workspace.mapper.MessageMapper;
import com.spring.gateway.workspace.mapper.MessageNodeMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话接口集成测试：鉴权拦截、统一返回体、时间戳序列化格式、越权防护。
 *
 * @author hanbing
 * @since 2026-09-26
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SessionControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private EngineClient engineClient;
    @Autowired
    private AuthSessionService jwtUtils;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SessionMapper sessionMapper;
    @Autowired
    private WorkMapper workMapper;
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private MessageNodeMapper messageNodeMapper;

    private Long insertUser() {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        userMapper.insert(user);
        return user.getId();
    }

    private String insertWork(Long userId) {
        Work work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(userId);
        work.setName("测试项目");
        work.setSizeBytes(0L);
        work.setIsDefault(false);
        work.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0, 0));
        work.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 8, 0, 0));
        workMapper.insert(work);
        return work.getId();
    }

    private String insertSession(Long userId, String workId) {
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setWorkId(workId);
        session.setCurrentSegment(0);
        session.setStatus(Session.STATUS_ACTIVE);
        session.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 8, 0, 0));
        sessionMapper.insert(session);
        return session.getId();
    }

    @Test
    @DisplayName("历史接口一次返回完整消息树")
    void historyReturnsCompleteTree() throws Exception {
        Long userId = insertUser();
        String sessionId = insertSession(userId, insertWork(userId));
        Session session = sessionMapper.selectById(sessionId);
        String originalId = UUID.randomUUID().toString();
        String retryId = UUID.randomUUID().toString();
        String answerGroupId = UUID.randomUUID().toString();
        insertNode(originalId, sessionId, UUID.randomUUID().toString(), answerGroupId,
                MessageNode.KIND_NORMAL, LocalDateTime.of(2026, 1, 1, 8, 1));
        insertNode(retryId, sessionId, UUID.randomUUID().toString(), answerGroupId,
                MessageNode.KIND_RETRY, LocalDateTime.of(2026, 1, 1, 8, 2));
        insertMessage(sessionId, originalId, "request", "user-prompt", "问题");
        insertMessage(sessionId, originalId, "response", "text", "回答 1");
        insertMessage(sessionId, retryId, "request", "user-prompt", "问题");
        insertMessage(sessionId, retryId, "response", "text", "回答 2");
        session.setActiveNodeId(retryId);
        sessionMapper.updateById(session);
        String token = jwtUtils.createSession(userId).token();

        mockMvc.perform(get("/api/sessions/{id}/history", sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(2))
                .andExpect(jsonPath("$.data.activeNodeId").value(retryId))
                .andExpect(jsonPath("$.data.nodes[?(@.branchId == '" + retryId + "')].answerVersion")
                        .value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$.data.nodes[?(@.branchId == '" + retryId + "')].retryCount")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.data.nodes[?(@.branchId == '" + originalId + "')].answerVersionCount")
                        .value(org.hamcrest.Matchers.contains(2)));
    }

    private void insertNode(String id, String sessionId, String groupId, String answerGroupId,
                            String kind, LocalDateTime createdAt) {
        MessageNode node = new MessageNode();
        node.setId(id);
        node.setSessionId(sessionId);
        node.setGroupId(groupId);
        node.setAnswerGroupId(answerGroupId);
        node.setKind(kind);
        node.setVersion(1);
        node.setSegmentNo(0);
        node.setCreatedAt(createdAt);
        messageNodeMapper.insert(node);
    }

    private void insertMessage(String sessionId, String nodeId, String kind, String partKind, String text) {
        Message message = new Message();
        message.setSessionId(sessionId);
        message.setNodeId(nodeId);
        message.setSegmentNo(0);
        message.setPayload("{\"kind\":\"" + kind + "\",\"parts\":[{\"part_kind\":\""
                + partKind + "\",\"content\":\"" + text + "\"}]}");
        message.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        messageMapper.insert(message);
    }

    @Test
    @DisplayName("缺少或非法 token 一律 401，返回统一结构")
    void unauthorized() throws Exception {
        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("合法 token 放行；时间戳序列化为带 Z 的 UTC 格式；越权访问返回 404")
    void authorizedFlow() throws Exception {
        Long me = insertUser();
        Long other = insertUser();
        String myWork = insertWork(me);
        String otherWork = insertWork(other);
        String mySession = insertSession(me, myWork);
        String othersSession = insertSession(other, otherWork);
        String token = jwtUtils.createSession(me).token();
        when(engineClient.busySessions(List.of(mySession))).thenReturn(Set.of(mySession));

        mockMvc.perform(get("/api/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].session_id").value(mySession))
                .andExpect(jsonPath("$.data[0].created_at").value("2026-01-01T08:00:00.000000Z"))
                .andExpect(jsonPath("$.data[0].busy").value(true));

        mockMvc.perform(get("/api/sessions/{id}", othersSession)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
