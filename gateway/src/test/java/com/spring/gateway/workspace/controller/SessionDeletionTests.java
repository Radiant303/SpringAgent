package com.spring.gateway.workspace.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.identity.service.AuthSessionService;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.identity.entity.*;
import com.spring.gateway.workspace.entity.*;
import com.spring.gateway.billing.entity.PointsLedger;
import com.spring.gateway.billing.entity.UsageRecord;
import com.spring.gateway.identity.mapper.*;
import com.spring.gateway.workspace.mapper.*;
import com.spring.gateway.storage.entity.FileRecord;
import com.spring.gateway.storage.mapper.FileRecordMapper;
import com.spring.gateway.billing.mapper.PointsLedgerMapper;
import com.spring.gateway.billing.mapper.UsageRecordMapper;
import com.spring.gateway.engine.client.EngineClient;
import com.spring.gateway.storage.service.UserStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话删除与存储计量测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SessionDeletionTests {
    @TempDir static Path root;
    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.data-root", () -> root.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired AuthSessionService jwt;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired UserMapper users;
    @Autowired WorkMapper works;
    @Autowired SessionMapper sessions;
    @Autowired MessageMapper messages;
    @Autowired FileRecordMapper files;
    @Autowired UsageRecordMapper usage;
    @Autowired PointsLedgerMapper ledger;
    @Autowired UserStorageService storage;
    @MockitoBean EngineClient engine;

    @org.junit.jupiter.api.BeforeEach
    void clearPreviousTransactionCalls() {
        clearInvocations(engine);
    }

    private record Fixture(User user, Work work, Session session, Message message) { }

    private Fixture fixture() {
        User user = new User();
        user.setId(ids.nextId());
        user.setUsername("delete_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setPointsBalance(new BigDecimal("25"));
        users.insert(user);
        Work work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(user.getId());
        work.setName("测试项目");
        work.setSizeBytes(0L);
        work.setInternalBytes(0L);
        work.setIsDefault(false);
        work.setCreatedAt(LocalDateTime.now());
        work.setUpdatedAt(LocalDateTime.now());
        works.insert(work);
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setWorkId(work.getId());
        session.setCurrentSegment(0);
        session.setStatus(Session.STATUS_ACTIVE);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessions.insert(session);
        Message message = new Message();
        message.setSessionId(session.getId());
        message.setSegmentNo(0);
        message.setPayload("{}");
        message.setCreatedAt(LocalDateTime.now());
        messages.insert(message);
        return new Fixture(user, work, session, message);
    }

    private String bearer(User user) {
        return "Bearer " + jwt.createSession(user.getId()).token();
    }

    private void uploadRecord(Fixture f, String type, long size, String name) {
        FileRecord file = new FileRecord();
        file.setId(ids.nextId());
        file.setUserId(f.user().getId());
        file.setSessionId(f.session().getId());
        file.setStorageType(type);
        file.setPath("works/" + f.work().getId() + "/" + f.session().getId() + "/images/" + name);
        file.setSizeBytes(size);
        file.setMimeType("image/png");
        file.setCreatedAt(LocalDateTime.now());
        files.insert(file);
    }

    @Test
    void softDeletesSessionCleansFilesAndPreservesOtherResourcesAndBilling() throws Exception {
        Fixture f = fixture();
        Path work = root.resolve("works").resolve(f.work().getId());
        Path directory = work.resolve(f.session().getId());
        Files.createDirectories(directory.resolve("images"));
        Files.write(directory.resolve("images/local.png"), new byte[5]);
        Files.write(directory.resolve("extra.txt"), new byte[7]);
        Files.write(work.resolve("shared.txt"), new byte[11]);
        Path knowledge = root.resolve("users").resolve(f.user().getId().toString()).resolve("knowledge");
        Files.createDirectories(knowledge);
        Files.write(knowledge.resolve("declarative.md"), new byte[13]);
        uploadRecord(f, "local", 999, "local.png");
        uploadRecord(f, "aliyun_oss", 17, "remote.png");
        var before = storage.snapshot(f.user());
        assertEquals(53L, before.usedBytes());
        UsageRecord record = new UsageRecord();
        record.setRequestId(UUID.randomUUID().toString());
        record.setTurnId("test-turn");
        record.setSessionId(f.session().getId());
        record.setUserId(f.user().getId());
        record.setModelId("test-model");
        record.setAgentName("main");
        record.setUsageKnown(true);
        record.setRequests(1);
        record.setStatus(UsageRecord.STATUS_FINISHED);
        record.setInputTokens(10L);
        record.setCacheReadTokens(0L);
        record.setCacheWriteTokens(0L);
        record.setOutputTokens(1L);
        record.setCostPoints(BigDecimal.ONE);
        record.setCreatedAt(LocalDateTime.now());
        usage.insert(record);
        long ledgerBefore = ledger.selectCount(new LambdaQueryWrapper<PointsLedger>().eq(PointsLedger::getUserId, f.user().getId()));

        mvc.perform(delete("/api/sessions/{id}", f.session().getId()).header(HttpHeaders.AUTHORIZATION, bearer(f.user())))
                .andExpect(status().isOk());

        assertEquals(Session.STATUS_DELETED, sessions.selectById(f.session().getId()).getStatus());
        assertNull(messages.selectById(f.message().getId()));
        assertEquals(0L, files.selectCount(new LambdaQueryWrapper<FileRecord>().eq(FileRecord::getSessionId, f.session().getId())));
        assertFalse(Files.exists(directory));
        assertTrue(Files.exists(work.resolve("shared.txt")));
        assertTrue(Files.exists(knowledge.resolve("declarative.md")));
        assertNotNull(usage.selectById(record.getId()));
        assertEquals(0, new BigDecimal("25").compareTo(users.selectById(f.user().getId()).getPointsBalance()));
        assertEquals(ledgerBefore, ledger.selectCount(new LambdaQueryWrapper<PointsLedger>().eq(PointsLedger::getUserId, f.user().getId())));
        assertEquals(24L, users.selectById(f.user().getId()).getUsedBytes());
        assertEquals(11L, works.selectById(f.work().getId()).getSizeBytes());
        mvc.perform(get("/api/sessions/{id}", f.session().getId()).header(HttpHeaders.AUTHORIZATION, bearer(f.user())))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/sessions/{id}", f.session().getId()).header(HttpHeaders.AUTHORIZATION, bearer(f.user())))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesSessionWithMissingDirectory() throws Exception {
        Fixture f = fixture();
        mvc.perform(delete("/api/sessions/{id}", f.session().getId()).header(HttpHeaders.AUTHORIZATION, bearer(f.user())))
                .andExpect(status().isOk());
        assertEquals(Session.STATUS_DELETED, sessions.selectById(f.session().getId()).getStatus());
        assertEquals(0L, users.selectById(f.user().getId()).getUsedBytes());
    }

    @Test
    void deniesAnotherUserBeforeEngineAccess() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        mvc.perform(delete("/api/sessions/{id}", f.session().getId()).header(HttpHeaders.AUTHORIZATION, bearer(other.user())))
                .andExpect(status().isNotFound());
        verifyNoInteractions(engine);
        assertEquals(Session.STATUS_ACTIVE, sessions.selectById(f.session().getId()).getStatus());
    }

    @Test
    void rejectsBusyEngineWithoutRemovingContent() throws Exception {
        Fixture f = fixture();
        doThrow(HttpClientErrorException.create(org.springframework.http.HttpStatus.CONFLICT, "busy", null, new byte[0], null))
                .when(engine).prepareSessionDeletion(eq(f.session().getId()), eq(f.user().getId()), anyString());
        mvc.perform(delete("/api/sessions/{id}", f.session().getId()).header(HttpHeaders.AUTHORIZATION, bearer(f.user())))
                .andExpect(status().isConflict());
        assertEquals(Session.STATUS_ACTIVE, sessions.selectById(f.session().getId()).getStatus());
        assertNotNull(messages.selectById(f.message().getId()));
    }
}
