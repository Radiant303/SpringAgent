package com.spring.gateway.controller;

import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.entity.User;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.PageMappingMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.service.EngineClient;
import com.spring.gateway.service.PageMappingService;
import com.spring.gateway.service.WorkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 页面映射随 work 生命周期的创建与删除测试。
 *
 * @author hanbing
 * @since 2026-10-16
 */
@SpringBootTest
@Transactional
class WorkPageMappingTests {

    @Autowired
    private WorkService workService;
    @Autowired
    private PageMappingService pageMappingService;
    @Autowired
    private PageMappingMapper pageMappingMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    @MockitoBean
    private EngineClient engineClient;

    @Value("${app.data-root}")
    private String dataRoot;

    private User createUser() {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setRole(User.ROLE_USER);
        user.setStatus(User.STATUS_ACTIVE);
        user.setPointsBalance(new BigDecimal("100"));
        userMapper.insert(user);
        return user;
    }

    @Test
    void createEstablishesMappingAndPageDir() {
        Work work = workService.create(createUser().getId(), "页面测试");

        Long pageId = pageMappingService.pageIdOf(work.getId());
        assertNotNull(pageId);
        assertNotNull(pageMappingMapper.selectById(pageId));
        assertTrue(Files.isDirectory(Path.of(dataRoot, "works", work.getId(), "page")));
    }

    @Test
    void ensureDefaultIsIdempotent() {
        User user = createUser();
        Work first = workService.ensureDefault(user.getId());
        Long pageId = pageMappingService.pageIdOf(first.getId());
        assertNotNull(pageId);

        Work second = workService.ensureDefault(user.getId());
        assertEquals(first.getId(), second.getId());
        assertEquals(pageId, pageMappingService.pageIdOf(second.getId()));
    }

    @Test
    void hardDeleteRemovesMapping() {
        User user = createUser();
        Work work = workService.create(user.getId(), "待删除");
        Long pageId = pageMappingService.pageIdOf(work.getId());
        assertNotNull(pageId);
        when(engineClient.activeSessionsInWork(work.getId())).thenReturn(0);

        workService.hardDelete(user.getId(), work.getId());

        assertNull(pageMappingService.pageIdOf(work.getId()));
        assertNull(pageMappingMapper.selectById(pageId));
    }
}
