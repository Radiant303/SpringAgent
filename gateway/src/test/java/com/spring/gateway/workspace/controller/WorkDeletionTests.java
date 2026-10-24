package com.spring.gateway.workspace.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.identity.service.AuthSessionService;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.workspace.entity.Message;
import com.spring.gateway.billing.entity.PointsLedger;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.billing.entity.UsageRecord;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.workspace.entity.Work;
import com.spring.gateway.workspace.mapper.MessageMapper;
import com.spring.gateway.billing.mapper.PointsLedgerMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.billing.mapper.UsageRecordMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import com.spring.gateway.billing.service.PointsAccountService;
import com.spring.gateway.billing.dto.ReserveModelRequest;
import com.spring.gateway.billing.dto.SettleModelRequest;
import com.spring.gateway.catalog.entity.ModelDefinition;
import com.spring.gateway.catalog.entity.ModelProvider;
import com.spring.gateway.catalog.entity.RateCard;
import com.spring.gateway.catalog.mapper.ModelDefinitionMapper;
import com.spring.gateway.catalog.mapper.ModelProviderMapper;
import com.spring.gateway.catalog.mapper.RateCardMapper;
import com.spring.gateway.engine.client.EngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目删除接口测试。
 *
 * @author hanbing
 * @since 2026-10-13
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkDeletionTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthSessionService jwtUtils;
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkMapper workMapper;
    @Autowired
    private SessionMapper sessionMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private UsageRecordMapper usageRecordMapper;
    @Autowired
    private PointsLedgerMapper ledgerMapper;
    @Autowired
    private PointsAccountService billingService;
    @Autowired
    private com.spring.gateway.billing.service.ModelBillingService modelBillingService;
    @MockitoBean
    private EngineClient engineClient;

    private record Fixture(User user, Work work, Session session, Message message) {
    }

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

    private Fixture fixture() {
        User user = createUser();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Work work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(user.getId());
        work.setName("删除回归测试");
        work.setSizeBytes(0L);
        work.setIsDefault(false);
        work.setCreatedAt(now);
        work.setUpdatedAt(now);
        workMapper.insert(work);
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setWorkId(work.getId());
        session.setCurrentSegment(0);
        session.setStatus(Session.STATUS_ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        Message message = new Message();
        message.setSessionId(session.getId());
        message.setSegmentNo(0);
        message.setPayload("{}");
        message.setCreatedAt(now);
        messageMapper.insert(message);
        return new Fixture(user, work, session, message);
    }

    @Autowired private ModelProviderMapper providerMapper;
    @Autowired private ModelDefinitionMapper modelMapper;
    @Autowired private RateCardMapper rateMapper;

    private ReserveModelRequest reserve(Fixture f) {
        var provider = new ModelProvider();
        provider.setName("t_" + UUID.randomUUID().toString().substring(0, 8));
        provider.setType("openai");
        provider.setApiKey("test-only");
        providerMapper.insert(provider);
        var model = new ModelDefinition();
        model.setId(provider.getName() + "/test");
        model.setProvider(provider.getName());
        model.setModel("test");
        model.setDisplayName("测试模型");
        model.setMaxContextSize(10000L);
        model.setMaxOutputSize(1000L);
        model.setEnabled(true);
        modelMapper.insert(model);
        var rate = new RateCard();
        rate.setModelId(model.getId());
        rate.setInputPoints(BigDecimal.TEN);
        rate.setCacheReadPoints(BigDecimal.ONE);
        rate.setCacheWritePoints(BigDecimal.TEN);
        rate.setOutputPoints(new BigDecimal("30"));
        rate.setEnabled(true);
        rate.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        rate.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        rateMapper.insert(rate);
        var request = new ReserveModelRequest(
                UUID.randomUUID().toString().replace("-", ""), f.user().getId(),
                String.valueOf(idGenerator.nextId()), f.session().getId(), model.getId(), "main");
        modelBillingService.reserveRequest(request);
        return request;
    }

    private SettleModelRequest usage(ReserveModelRequest request) {
        return new SettleModelRequest(request.requestId(), request.userId(),
                "finished", true, 1000L, 0L, 0L, 100L, null);
    }

    private UsageRecord record(String requestId) {
        return usageRecordMapper.selectOne(new LambdaQueryWrapper<UsageRecord>()
                .eq(UsageRecord::getRequestId, requestId));
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtils.createSession(user.getId()).token();
    }

    private long ledgerCount(User user) {
        return ledgerMapper.selectCount(new LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getUserId, user.getId()));
    }

    @Test
    void deletesContentButPreservesBillingAndAcceptsLateUsage() throws Exception {
        Fixture f = fixture();
        var originalRequest = reserve(f);
        var originalUsage = usage(originalRequest);
        modelBillingService.settleRequest(originalUsage);
        UsageRecord original = record(originalRequest.requestId());
        var lateRequest = reserve(f);
        var lateUsage = usage(lateRequest);
        BigDecimal balanceBefore = userMapper.selectById(f.user().getId()).getPointsBalance();
        long ledgerBefore = ledgerCount(f.user());
        when(engineClient.activeSessionsInWork(f.work().getId())).thenReturn(0);

        mockMvc.perform(delete("/api/works/{id}", f.work().getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(f.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(workMapper.selectById(f.work().getId()));
        assertNull(sessionMapper.selectById(f.session().getId()));
        assertNull(messageMapper.selectById(f.message().getId()));
        UsageRecord preserved = usageRecordMapper.selectById(original.getId());
        assertNotNull(preserved);
        assertEquals(f.session().getId(), preserved.getSessionId());
        assertEquals(original.getModelId(), preserved.getModelId());
        assertEquals(original.getInputTokens(), preserved.getInputTokens());
        assertEquals(balanceBefore, userMapper.selectById(f.user().getId()).getPointsBalance());
        assertEquals(ledgerBefore, ledgerCount(f.user()));

        modelBillingService.settleRequest(originalUsage);
        assertEquals(balanceBefore, userMapper.selectById(f.user().getId()).getPointsBalance());
        modelBillingService.settleRequest(lateUsage);
        assertNotNull(record(lateRequest.requestId()));
        assertEquals(ledgerBefore + 1, ledgerCount(f.user()));
        BigDecimal settled = userMapper.selectById(f.user().getId()).getPointsBalance();
        modelBillingService.settleRequest(lateUsage);
        assertEquals(settled, userMapper.selectById(f.user().getId()).getPointsBalance());
        assertEquals(ledgerBefore + 1, ledgerCount(f.user()));
        mockMvc.perform(get("/api/works")
                        .header(HttpHeaders.AUTHORIZATION, bearer(f.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void rejectsAnotherUsersProject() throws Exception {
        Fixture f = fixture();
        User other = createUser();
        mockMvc.perform(delete("/api/works/{id}", f.work().getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNotFound());
        assertNotNull(workMapper.selectById(f.work().getId()));
        verifyNoInteractions(engineClient);
    }

    @Test
    void rejectsActiveProjectWithoutDeletingContent() throws Exception {
        Fixture f = fixture();
        when(engineClient.activeSessionsInWork(f.work().getId())).thenReturn(1);
        mockMvc.perform(delete("/api/works/{id}", f.work().getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(f.user())))
                .andExpect(status().isConflict());
        assertNotNull(workMapper.selectById(f.work().getId()));
        assertNotNull(sessionMapper.selectById(f.session().getId()));
        assertNotNull(messageMapper.selectById(f.message().getId()));
    }

    @Test
    void rejectsDeletionWhenEngineIsUnavailable() throws Exception {
        Fixture f = fixture();
        when(engineClient.activeSessionsInWork(f.work().getId()))
                .thenThrow(new IllegalStateException("测试引擎不可达"));
        mockMvc.perform(delete("/api/works/{id}", f.work().getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(f.user())))
                .andExpect(status().isServiceUnavailable());
        assertNotNull(workMapper.selectById(f.work().getId()));
        assertNotNull(sessionMapper.selectById(f.session().getId()));
    }
}
