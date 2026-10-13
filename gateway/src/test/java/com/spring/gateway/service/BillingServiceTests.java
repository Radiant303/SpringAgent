package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.dto.ReserveModelRequest;
import com.spring.gateway.dto.SettleModelRequest;
import com.spring.gateway.entity.ModelDefinition;
import com.spring.gateway.entity.ModelProvider;
import com.spring.gateway.entity.PointsHold;
import com.spring.gateway.entity.PointsLedger;
import com.spring.gateway.entity.RateCard;
import com.spring.gateway.entity.Session;
import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.entity.UsageRecord;
import com.spring.gateway.entity.User;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.ModelDefinitionMapper;
import com.spring.gateway.mapper.ModelProviderMapper;
import com.spring.gateway.mapper.PointsHoldMapper;
import com.spring.gateway.mapper.PointsLedgerMapper;
import com.spring.gateway.mapper.RateCardMapper;
import com.spring.gateway.mapper.SessionMapper;
import com.spring.gateway.mapper.UsageRecordMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.mapper.WorkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单次模型请求计费测试。
 *
 * @author hanbing
 * @since 2026-10-13
 */
@SpringBootTest
@Transactional
class BillingServiceTests {
    @Autowired private BillingService billingService;
    @Autowired private PointsHoldMapper holdMapper;
    @Autowired private PointsLedgerMapper ledgerMapper;
    @Autowired private RateCardMapper rateCardMapper;
    @Autowired private UsageRecordMapper usageMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkMapper workMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private ModelProviderMapper providerMapper;
    @Autowired private ModelDefinitionMapper modelMapper;
    @Autowired private SnowflakeIdGenerator ids;
    @Autowired private SystemSettingService settings;
    private long userId;
    private String sessionId;
    private String modelId;

    @BeforeEach
    void fixture() {
        User user = new User();
        user.setId(ids.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setStatus(User.STATUS_ACTIVE);
        user.setPointsBalance(BigDecimal.TEN);
        userMapper.insert(user);
        userId = user.getId();
        Work work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(userId);
        work.setName("测试项目");
        work.setSizeBytes(0L);
        work.setIsDefault(false);
        workMapper.insert(work);
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setWorkId(work.getId());
        session.setCurrentSegment(0);
        session.setStatus(Session.STATUS_ACTIVE);
        sessionMapper.insert(session);
        sessionId = session.getId();
        ModelProvider provider = new ModelProvider();
        provider.setName("t_" + UUID.randomUUID().toString().substring(0, 8));
        provider.setType("openai");
        provider.setApiKey("test-only");
        providerMapper.insert(provider);
        modelId = provider.getName() + "/test-model";
        ModelDefinition model = new ModelDefinition();
        model.setId(modelId);
        model.setProvider(provider.getName());
        model.setModel("test-model");
        model.setDisplayName("测试模型");
        model.setMaxContextSize(1000000L);
        model.setMaxOutputSize(20000L);
        model.setEnabled(true);
        modelMapper.insert(model);
        RateCard rate = new RateCard();
        rate.setModelId(modelId);
        rate.setInputPoints(BigDecimal.TEN);
        rate.setCacheReadPoints(BigDecimal.ONE);
        rate.setCacheWritePoints(new BigDecimal("20"));
        rate.setOutputPoints(new BigDecimal("30"));
        rate.setEnabled(true);
        rate.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        rate.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        rateCardMapper.insert(rate);
        settings.set(SystemSetting.KEY_BILLING_EST_CACHE_READ_TOKENS, "90000");
        settings.set(SystemSetting.KEY_BILLING_EST_INPUT_TOKENS, "10000");
        settings.set(SystemSetting.KEY_BILLING_EST_OUTPUT_TOKENS, "20000");
    }

    private ReserveModelRequest request(String turn, String agent, boolean wake) {
        return new ReserveModelRequest(UUID.randomUUID().toString().replace("-", ""), userId,
                turn, sessionId, modelId, agent, wake);
    }

    private SettleModelRequest usage(ReserveModelRequest request, long input, long cr, long cw, long output) {
        return new SettleModelRequest(request.requestId(), request.userId(), "finished", true,
                input, cr, cw, output);
    }

    private void amount(String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(billingService.balanceOf(userId)));
    }

    private long usageCount() {
        return usageMapper.selectCount(new LambdaQueryWrapper<UsageRecord>().eq(UsageRecord::getUserId, userId));
    }

    @Test
    void everyRequestReservesAndSettlesImmediately() {
        ReserveModelRequest first = request("same-turn", "main", false);
        billingService.reserveRequest(first);
        billingService.reserveRequest(first);
        amount("9.21");
        billingService.settleRequest(usage(first, 1000, 0, 0, 100));
        amount("9.987");
        ReserveModelRequest next = request("same-turn", "main", false);
        billingService.reserveRequest(next);
        billingService.settleRequest(usage(next, 1000, 0, 0, 100));
        amount("9.974");
        assertEquals(2, usageCount());
        assertEquals(PointsHold.STATUS_SETTLED, holdMapper.selectById(first.requestId()).getStatus());
    }

    @Test
    void insufficientBalanceHasNoSideEffects() {
        userMapper.updateBalance(userId, new BigDecimal("0.5"));
        ReserveModelRequest request = request("turn", "main", false);
        BizException error = assertThrows(BizException.class, () -> billingService.reserveRequest(request));
        assertEquals(409, error.getStatus());
        assertNull(holdMapper.selectById(request.requestId()));
        assertEquals(0, ledgerMapper.selectCount(new LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getUserId, userId)));
        amount("0.5");
    }

    @Test
    void cachesAreNotChargedAsUncachedInput() {
        ReserveModelRequest request = request("turn", "main", false);
        billingService.reserveRequest(request);
        billingService.settleRequest(usage(request, 1300, 100, 200, 100));
        amount("9.9829");
    }

    @Test
    void retriesAreIdempotentAndConflictsRejected() {
        ReserveModelRequest request = request("turn", "main", false);
        billingService.reserveRequest(request);
        SettleModelRequest usage = usage(request, 1000, 0, 0, 100);
        billingService.settleRequest(usage);
        billingService.settleRequest(usage);
        amount("9.987");
        assertEquals(1, usageCount());
        assertEquals(409, assertThrows(BizException.class,
                () -> billingService.settleRequest(usage(request, 2000, 0, 0, 100))).getStatus());
        assertThrows(BizException.class, () -> billingService.reserveRequest(request));
    }

    @Test
    void priceSnapshotSurvivesRateChanges() {
        ReserveModelRequest request = request("turn", "reviewer", false);
        billingService.reserveRequest(request);
        RateCard rate = rateCardMapper.selectById(modelId);
        rate.setInputPoints(new BigDecimal("10000"));
        rateCardMapper.updateById(rate);
        billingService.settleRequest(usage(request, 1000, 0, 0, 100));
        amount("9.987");
    }

    @Test
    void unknownUsageKeepsReservationAndCanBeReconciled() {
        ReserveModelRequest request = request("turn", "main", false);
        billingService.reserveRequest(request);
        billingService.settleRequest(new SettleModelRequest(request.requestId(), userId, "error",
                false, 0L, 0L, 0L, 0L));
        amount("9.21");
        assertEquals(PointsHold.STATUS_UNKNOWN, holdMapper.selectById(request.requestId()).getStatus());
        billingService.settleRequest(usage(request, 1000, 0, 0, 100));
        amount("9.987");
        assertEquals(1, usageCount());
    }

    @Test
    void staleReservationsAreNotAutomaticallyRefunded() {
        ReserveModelRequest request = request("turn", "main", false);
        billingService.reserveRequest(request);
        billingService.markStale(request.requestId());
        amount("9.21");
        billingService.settleRequest(usage(request, 1000, 0, 0, 100));
        billingService.markStale(request.requestId());
        amount("9.987");
    }

    @Test
    void explicitFailureRefundsAndCancellationBillsKnownTokens() {
        ReserveModelRequest failure = request("turn", "main", false);
        billingService.reserveRequest(failure);
        billingService.settleRequest(new SettleModelRequest(failure.requestId(), userId, "error",
                true, 0L, 0L, 0L, 0L));
        amount("10");
        ReserveModelRequest cancelled = request("turn", "main", false);
        billingService.reserveRequest(cancelled);
        billingService.settleRequest(new SettleModelRequest(cancelled.requestId(), userId, "cancelled",
                true, 1000L, 0L, 0L, 100L));
        amount("9.987");
    }

    @Test
    void overspendBlocksNextRequest() {
        ReserveModelRequest request = request("turn", "main", false);
        billingService.reserveRequest(request);
        billingService.settleRequest(usage(request, 1000000, 0, 0, 1000000));
        amount("-30");
        assertThrows(BizException.class, () -> billingService.reserveRequest(request("turn", "searcher", false)));
    }

    @Test
    void wakeUsesTheSameRequestBilling() {
        ReserveModelRequest request = request("wake-test", "main", true);
        billingService.reserveRequest(request);
        billingService.settleRequest(usage(request, 1000, 0, 0, 100));
        UsageRecord record = usageMapper.selectOne(new LambdaQueryWrapper<UsageRecord>()
                .eq(UsageRecord::getRequestId, request.requestId()));
        assertTrue(record.getIsWake());
        assertEquals("main", record.getAgentName());
        amount("9.987");
    }

    @Test
    void rejectsInvalidCacheAndUnreservedUsage() {
        ReserveModelRequest request = request("turn", "main", false);
        assertThrows(BizException.class, () -> billingService.settleRequest(usage(request, 1, 0, 0, 1)));
        billingService.reserveRequest(request);
        assertThrows(BizException.class, () -> billingService.settleRequest(usage(request, 1, 2, 0, 1)));
        amount("9.21");
    }

    @Test
    void mainAndSubagentUseDifferentModelRatesInOneTurn() {
        String childId = modelId.replace("/test-model", "/child");
        ModelDefinition original = modelMapper.selectById(modelId);
        ModelDefinition child = new ModelDefinition();
        child.setId(childId);
        child.setProvider(original.getProvider());
        child.setModel("child");
        child.setDisplayName("子代理模型");
        child.setMaxContextSize(1000000L);
        child.setMaxOutputSize(20000L);
        child.setEnabled(true);
        modelMapper.insert(child);
        RateCard rate = new RateCard();
        rate.setModelId(childId);
        rate.setInputPoints(new BigDecimal("100"));
        rate.setCacheReadPoints(new BigDecimal("10"));
        rate.setCacheWritePoints(new BigDecimal("200"));
        rate.setOutputPoints(new BigDecimal("300"));
        rate.setEnabled(true);
        rate.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        rate.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        rateCardMapper.insert(rate);
        ReserveModelRequest main = request("same-turn", "main", false);
        billingService.reserveRequest(main);
        billingService.settleRequest(usage(main, 1000, 0, 0, 100));
        ReserveModelRequest subagent = new ReserveModelRequest(UUID.randomUUID().toString().replace("-", ""),
                userId, "same-turn", sessionId, childId, "reviewer", false);
        billingService.reserveRequest(subagent);
        billingService.settleRequest(usage(subagent, 1000, 0, 0, 100));
        amount("9.857");
        assertEquals(2, usageCount());
    }

    @Test
    void rejectsCrossUserReservationAndRequestIdConflicts() {
        ReserveModelRequest request = request("turn", "main", false);
        billingService.reserveRequest(request);
        ReserveModelRequest conflict = new ReserveModelRequest(request.requestId(), userId,
                "another-turn", sessionId, modelId, "main", false);
        assertThrows(BizException.class, () -> billingService.reserveRequest(conflict));
        User other = new User();
        other.setId(ids.nextId());
        other.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        other.setPasswordHash("test-only");
        other.setStatus(User.STATUS_ACTIVE);
        other.setPointsBalance(BigDecimal.TEN);
        userMapper.insert(other);
        ReserveModelRequest unauthorized = new ReserveModelRequest(UUID.randomUUID().toString().replace("-", ""),
                other.getId(), "turn", sessionId, modelId, "main", false);
        assertEquals(404, assertThrows(BizException.class, () -> billingService.reserveRequest(unauthorized)).getStatus());
        assertEquals(0, billingService.balanceOf(other.getId()).compareTo(BigDecimal.TEN));
    }

    @Test
    void adjustmentsRemainAtomic() {
        billingService.adjust(userId, userId, new BigDecimal("100"), "充值");
        amount("110");
        assertThrows(BizException.class, () -> billingService.adjust(userId, userId,
                new BigDecimal("-200"), "扣减"));
        assertThrows(BizException.class, () -> billingService.adjust(userId, userId, BigDecimal.ZERO, "空"));
        amount("110");
    }
}
