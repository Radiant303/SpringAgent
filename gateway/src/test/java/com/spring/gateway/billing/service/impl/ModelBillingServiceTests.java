package com.spring.gateway.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.billing.dto.ReserveModelRequest;
import com.spring.gateway.search.dto.SearchUsageEvent;
import com.spring.gateway.billing.dto.SettleModelRequest;
import com.spring.gateway.catalog.entity.ModelDefinition;
import com.spring.gateway.catalog.entity.ModelProvider;
import com.spring.gateway.billing.entity.PointsHold;
import com.spring.gateway.billing.entity.PointsLedger;
import com.spring.gateway.catalog.entity.RateCard;
import com.spring.gateway.search.entity.SearchUsageRecord;
import com.spring.gateway.workspace.entity.Session;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.billing.entity.UsageRecord;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.workspace.entity.Work;
import com.spring.gateway.catalog.mapper.ModelDefinitionMapper;
import com.spring.gateway.catalog.mapper.ModelProviderMapper;
import com.spring.gateway.billing.mapper.PointsHoldMapper;
import com.spring.gateway.billing.mapper.PointsLedgerMapper;
import com.spring.gateway.catalog.mapper.RateCardMapper;
import com.spring.gateway.search.mapper.SearchUsageRecordMapper;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.billing.mapper.UsageRecordMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import com.spring.gateway.billing.service.ModelBillingService;
import com.spring.gateway.billing.service.PointsAccountService;
import com.spring.gateway.search.service.SearchBillingService;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.settings.service.SystemSettingWriter;
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
class ModelBillingServiceTests {
    @Autowired private ModelBillingService billingService;
    @Autowired private PointsAccountService pointsAccountService;
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
    @Autowired private SystemSettingWriter settingWriter;
    @Autowired private SearchBillingService searchBilling;
    @Autowired private SearchUsageRecordMapper searchRecords;
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
        settingWriter.set(SystemSetting.KEY_BILLING_EST_CACHE_READ_TOKENS, "90000");
        settingWriter.set(SystemSetting.KEY_BILLING_EST_INPUT_TOKENS, "10000");
        settingWriter.set(SystemSetting.KEY_BILLING_EST_OUTPUT_TOKENS, "20000");
    }

    private ReserveModelRequest request(String turn, String agent) {
        return new ReserveModelRequest(UUID.randomUUID().toString().replace("-", ""), userId,
                turn, sessionId, modelId, agent);
    }

    private SettleModelRequest usage(ReserveModelRequest request, long input, long cr, long cw, long output) {
        return new SettleModelRequest(request.requestId(), request.userId(), "finished", true,
                input, cr, cw, output, null);
    }

    private void amount(String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(pointsAccountService.balanceOf(userId)));
    }

    private long usageCount() {
        return usageMapper.selectCount(new LambdaQueryWrapper<UsageRecord>().eq(UsageRecord::getUserId, userId));
    }

    @Test
    void everyRequestReservesAndSettlesImmediately() {
        ReserveModelRequest first = request("same-turn", "main");
        billingService.reserveRequest(first);
        billingService.reserveRequest(first);
        amount("9.21");
        billingService.settleRequest(usage(first, 1000, 0, 0, 100));
        amount("9.987");
        ReserveModelRequest next = request("same-turn", "main");
        billingService.reserveRequest(next);
        billingService.settleRequest(usage(next, 1000, 0, 0, 100));
        amount("9.974");
        assertEquals(2, usageCount());
        assertEquals(PointsHold.STATUS_SETTLED, holdMapper.selectById(first.requestId()).getStatus());
    }

    @Test
    void insufficientBalanceHasNoSideEffects() {
        userMapper.updateBalance(userId, new BigDecimal("0.5"));
        ReserveModelRequest request = request("turn", "main");
        BizException error = assertThrows(BizException.class, () -> billingService.reserveRequest(request));
        assertEquals(409, error.getStatus());
        assertNull(holdMapper.selectById(request.requestId()));
        assertEquals(0, ledgerMapper.selectCount(new LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getUserId, userId)));
        amount("0.5");
    }

    @Test
    void cachesAreNotChargedAsUncachedInput() {
        ReserveModelRequest request = request("turn", "main");
        billingService.reserveRequest(request);
        billingService.settleRequest(usage(request, 1300, 100, 200, 100));
        amount("9.9829");
    }

    @Test
    void retriesAreIdempotentAndConflictsRejected() {
        ReserveModelRequest request = request("turn", "main");
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
        ReserveModelRequest request = request("turn", "reviewer");
        billingService.reserveRequest(request);
        RateCard rate = rateCardMapper.selectById(modelId);
        rate.setInputPoints(new BigDecimal("10000"));
        rateCardMapper.updateById(rate);
        billingService.settleRequest(usage(request, 1000, 0, 0, 100));
        amount("9.987");
    }

    @Test
    void unknownUsageKeepsReservationAndCanBeReconciled() {
        ReserveModelRequest request = request("turn", "main");
        billingService.reserveRequest(request);
        billingService.settleRequest(new SettleModelRequest(request.requestId(), userId, "error",
                false, 0L, 0L, 0L, 0L, null));
        amount("9.21");
        assertEquals(PointsHold.STATUS_UNKNOWN, holdMapper.selectById(request.requestId()).getStatus());
        billingService.settleRequest(usage(request, 1000, 0, 0, 100));
        amount("9.987");
        assertEquals(1, usageCount());
    }

    @Test
    void staleReservationsAreNotAutomaticallyRefunded() {
        ReserveModelRequest request = request("turn", "main");
        billingService.reserveRequest(request);
        billingService.markStale(request.requestId());
        amount("9.21");
        billingService.settleRequest(usage(request, 1000, 0, 0, 100));
        billingService.markStale(request.requestId());
        amount("9.987");
    }

    @Test
    void explicitFailureRefundsAndCancellationBillsKnownTokens() {
        ReserveModelRequest failure = request("turn", "main");
        billingService.reserveRequest(failure);
        billingService.settleRequest(new SettleModelRequest(failure.requestId(), userId, "error",
                true, 0L, 0L, 0L, 0L, null));
        amount("10");
        ReserveModelRequest cancelled = request("turn", "main");
        billingService.reserveRequest(cancelled);
        billingService.settleRequest(new SettleModelRequest(cancelled.requestId(), userId, "cancelled",
                true, 1000L, 0L, 0L, 100L, null));
        amount("9.987");
    }

    @Test
    void overspendBlocksNextRequest() {
        ReserveModelRequest request = request("turn", "main");
        billingService.reserveRequest(request);
        billingService.settleRequest(usage(request, 1000000, 0, 0, 1000000));
        amount("-30");
        assertThrows(BizException.class, () -> billingService.reserveRequest(request("turn", "searcher")));
    }

    @Test
    void rejectsInvalidCacheAndUnreservedUsage() {
        ReserveModelRequest request = request("turn", "main");
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
        ReserveModelRequest main = request("same-turn", "main");
        billingService.reserveRequest(main);
        billingService.settleRequest(usage(main, 1000, 0, 0, 100));
        ReserveModelRequest subagent = new ReserveModelRequest(UUID.randomUUID().toString().replace("-", ""),
                userId, "same-turn", sessionId, childId, "reviewer");
        billingService.reserveRequest(subagent);
        billingService.settleRequest(usage(subagent, 1000, 0, 0, 100));
        amount("9.857");
        assertEquals(2, usageCount());
    }

    @Test
    void rejectsCrossUserReservationAndRequestIdConflicts() {
        ReserveModelRequest request = request("turn", "main");
        billingService.reserveRequest(request);
        ReserveModelRequest conflict = new ReserveModelRequest(request.requestId(), userId,
                "another-turn", sessionId, modelId, "main");
        assertThrows(BizException.class, () -> billingService.reserveRequest(conflict));
        User other = new User();
        other.setId(ids.nextId());
        other.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        other.setPasswordHash("test-only");
        other.setStatus(User.STATUS_ACTIVE);
        other.setPointsBalance(BigDecimal.TEN);
        userMapper.insert(other);
        ReserveModelRequest unauthorized = new ReserveModelRequest(UUID.randomUUID().toString().replace("-", ""),
                other.getId(), "turn", sessionId, modelId, "main");
        assertEquals(404, assertThrows(BizException.class, () -> billingService.reserveRequest(unauthorized)).getStatus());
        assertEquals(0, pointsAccountService.balanceOf(other.getId()).compareTo(BigDecimal.TEN));
    }

    private SettleModelRequest searchUsage(ReserveModelRequest request, String status,
                                          boolean known, long input, Long searches) {
        return new SettleModelRequest(request.requestId(), userId, status, known,
                input, 0L, 0L, 0L, searches);
    }

    @Test
    void tokenAndSearchInterleavingNeverOverwritesBalanceOrAggregatesAgents() {
        ReserveModelRequest main = request("same-turn", "main");
        ReserveModelRequest child = request("same-turn", "reviewer");
        billingService.reserveRequest(main);
        billingService.reserveRequest(child);
        SearchUsageEvent local = new SearchUsageEvent("local:" + main.requestId(), "local", "exa", 1L,
                userId, sessionId, "same-turn", main.requestId(), "main");
        searchBilling.chargeLocal(local);
        SettleModelRequest mainUsage = searchUsage(main, "finished", true, 1000, 2L);
        billingService.settleRequest(mainUsage);
        billingService.settleRequest(searchUsage(child, "finished", true, 2000, 1L));
        billingService.settleRequest(mainUsage);
        searchBilling.chargeLocal(local);
        BigDecimal expected = new BigDecimal("9.97").subtract(settings.getSearchPoints("exa"))
                .subtract(settings.getSearchPoints("native").multiply(BigDecimal.valueOf(3)));
        assertEquals(0, expected.compareTo(pointsAccountService.balanceOf(userId)));
        assertEquals(2, usageCount());
        assertEquals(3, searchRecords.selectCount(new LambdaQueryWrapper<SearchUsageRecord>()
                .eq(SearchUsageRecord::getUserId, userId)));
    }

    @Test
    void knownTokensAllowLateSearchReportWithoutRepeatingTokenSettlement() {
        ReserveModelRequest request = request("turn", "main");
        billingService.reserveRequest(request);
        billingService.settleRequest(searchUsage(request, "finished", true, 1000, null));
        assertNull(searchRecords.selectById("native:" + request.requestId()));
        billingService.settleRequest(searchUsage(request, "finished", true, 1000, 2L));
        billingService.settleRequest(searchUsage(request, "finished", true, 1000, null));
        billingService.settleRequest(searchUsage(request, "finished", true, 1000, 2L));
        BigDecimal expected = new BigDecimal("9.99")
                .subtract(settings.getSearchPoints("native").multiply(BigDecimal.valueOf(2)));
        assertEquals(0, expected.compareTo(pointsAccountService.balanceOf(userId)));
        assertEquals(1, usageCount());
        assertEquals(2L, searchRecords.selectById("native:" + request.requestId()).getSearches());
    }

    @Test
    void unknownTokensStillChargeReportedSearchAndLaterReconcileTokens() {
        ReserveModelRequest request = request("turn", "main");
        billingService.reserveRequest(request);
        billingService.settleRequest(searchUsage(request, "error", false, 0, 3L));
        BigDecimal searchCost = settings.getSearchPoints("native").multiply(BigDecimal.valueOf(3));
        assertEquals(0, new BigDecimal("9.21").subtract(searchCost)
                .compareTo(pointsAccountService.balanceOf(userId)));
        assertEquals(PointsHold.STATUS_UNKNOWN, holdMapper.selectById(request.requestId()).getStatus());
        billingService.settleRequest(searchUsage(request, "finished", true, 1000, 3L));
        assertEquals(0, new BigDecimal("9.99").subtract(searchCost)
                .compareTo(pointsAccountService.balanceOf(userId)));
        assertEquals(1, usageCount());
    }

    @Test
    void repeatedUnknownTokensDoNotSkipNewSearchInformation() {
        ReserveModelRequest request = request("turn", "main");
        billingService.reserveRequest(request);
        billingService.settleRequest(searchUsage(request, "error", false, 0, null));
        billingService.settleRequest(searchUsage(request, "error", false, 0, 2L));
        billingService.settleRequest(searchUsage(request, "error", false, 0, 2L));
        BigDecimal expected = new BigDecimal("9.21")
                .subtract(settings.getSearchPoints("native").multiply(BigDecimal.valueOf(2)));
        assertEquals(0, expected.compareTo(pointsAccountService.balanceOf(userId)));
        assertEquals(2L, searchRecords.selectById("native:" + request.requestId()).getSearches());
    }

    @Test
    void errorAndCancellationStillChargeReportedSearches() {
        ReserveModelRequest failure = request("turn", "main");
        ReserveModelRequest cancelled = request("turn", "reviewer");
        billingService.reserveRequest(failure);
        billingService.reserveRequest(cancelled);
        billingService.settleRequest(searchUsage(failure, "error", true, 0, 2L));
        billingService.settleRequest(searchUsage(cancelled, "cancelled", true, 1000, 2L));
        BigDecimal expected = new BigDecimal("9.99")
                .subtract(settings.getSearchPoints("native").multiply(BigDecimal.valueOf(4)));
        assertEquals(0, expected.compareTo(pointsAccountService.balanceOf(userId)));
        assertEquals(2, searchRecords.selectCount(new LambdaQueryWrapper<SearchUsageRecord>()
                .eq(SearchUsageRecord::getUserId, userId)));
    }

    @Test
    void knownZeroSearchCountCannotBeChangedOnReplay() {
        ReserveModelRequest request = request("turn", "main");
        billingService.reserveRequest(request);
        billingService.settleRequest(searchUsage(request, "finished", true, 1000, 0L));
        billingService.settleRequest(searchUsage(request, "finished", true, 1000, 0L));
        assertEquals(0L, searchRecords.selectById("native:" + request.requestId()).getSearches());
        assertEquals(0, ledgerMapper.selectCount(new LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getUserId, userId).eq(PointsLedger::getType, PointsLedger.TYPE_SEARCH)));
        assertEquals(409, assertThrows(BizException.class,
                () -> billingService.settleRequest(searchUsage(request, "finished", true, 1000, 1L))).getStatus());
        amount("9.99");
    }

    @Test
    void adjustmentsRemainAtomic() {
        pointsAccountService.adjust(userId, userId, new BigDecimal("100"), "充值");
        amount("110");
        assertThrows(BizException.class, () -> pointsAccountService.adjust(userId, userId,
                new BigDecimal("-200"), "扣减"));
        assertThrows(BizException.class, () -> pointsAccountService.adjust(userId, userId, BigDecimal.ZERO, "空"));
        amount("110");
    }
}
