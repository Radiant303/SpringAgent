package com.spring.gateway.workspace.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.workspace.dto.SessionSummary;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.workspace.entity.Work;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import com.spring.gateway.workspace.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 会话直查业务集成测试。@Transactional 保证用例结束后回滚。
 *
 * @author hanbing
 * @since 2026-09-26
 */
@SpringBootTest
@Transactional
class SessionServiceTests {

    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionMapper sessionMapper;
    @Autowired
    private WorkMapper workMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SnowflakeIdGenerator idGenerator;

    /**
     * 插入测试用户
     *
     * @return 用户 ID
     */
    private Long insertUser() {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        userMapper.insert(user);
        return user.getId();
    }

    /**
     * 插入测试 work
     *
     * @param userId 所属用户 ID
     * @return work ID
     */
    private String insertWork(Long userId) {
        Work work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(userId);
        work.setName("测试项目");
        work.setSizeBytes(0L);
        work.setIsDefault(false);
        work.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        work.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        workMapper.insert(work);
        return work.getId();
    }

    /**
     * 插入测试会话
     *
     * @param userId    所属用户 ID
     * @param workId    所属 work ID
     * @param status    状态
     * @param updatedAt 更新时间
     * @return 会话 ID
     */
    private String insertSession(Long userId, String workId, String status, LocalDateTime updatedAt) {
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setWorkId(workId);
        session.setCurrentSegment(0);
        session.setStatus(status);
        session.setCreatedAt(updatedAt);
        session.setUpdatedAt(updatedAt);
        sessionMapper.insert(session);
        return session.getId();
    }

    @Test
    @DisplayName("列表只含本人未删除会话，按更新时间倒序")
    void listFiltersAndOrders() {
        Long me = insertUser();
        Long other = insertUser();
        String myWork = insertWork(me);
        String otherWork = insertWork(other);
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        String oldId = insertSession(me, myWork, Session.STATUS_ACTIVE, base);
        String newId = insertSession(me, myWork, Session.STATUS_ACTIVE, base.plusSeconds(1));
        insertSession(me, myWork, Session.STATUS_DELETED, base.plusSeconds(2));
        insertSession(other, otherWork, Session.STATUS_ACTIVE, base.plusSeconds(3));

        List<SessionSummary> all = sessionService.list(me, null);
        assertEquals(List.of(newId, oldId), all.stream().map(SessionSummary::sessionId).toList());

        List<SessionSummary> filtered = sessionService.list(me, myWork);
        assertEquals(List.of(newId, oldId), filtered.stream().map(SessionSummary::sessionId).toList());
        assertEquals(List.of(), sessionService.list(me, otherWork));
    }

    @Test
    @DisplayName("详情：不存在、已删除、他人会话统一 404")
    void detailNotFoundCases() {
        Long me = insertUser();
        Long other = insertUser();
        String myWork = insertWork(me);
        String otherWork = insertWork(other);
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        String mine = insertSession(me, myWork, Session.STATUS_ACTIVE, now);
        String deleted = insertSession(me, myWork, Session.STATUS_DELETED, now);
        String others = insertSession(other, otherWork, Session.STATUS_ACTIVE, now);

        assertEquals(mine, sessionService.detail(me, mine).sessionId());

        for (String id : List.of(deleted, others, UUID.randomUUID().toString())) {
            BizException e = assertThrows(BizException.class, () -> sessionService.detail(me, id));
            assertEquals(404, e.getStatus(), "会话 " + id + " 应返回 404");
        }
    }
}
