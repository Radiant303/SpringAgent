package com.spring.gateway.service;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.entity.PointsHold;
import com.spring.gateway.entity.PointsLedger;
import com.spring.gateway.entity.RateCard;
import com.spring.gateway.entity.Session;
import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.entity.UsageRecord;
import com.spring.gateway.entity.User;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.PointsHoldMapper;
import com.spring.gateway.mapper.PointsLedgerMapper;
import com.spring.gateway.mapper.RateCardMapper;
import com.spring.gateway.mapper.SessionMapper;
import com.spring.gateway.mapper.UsageRecordMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.mapper.WorkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 积分计费集成测试。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@SpringBootTest
@Transactional
class BillingServiceTests {

    /** 固定测试配置下的预扣估算额。 */
    private static final BigDecimal EST_HOLD = new BigDecimal("0.790000");

    @Autowired
    private BillingService billingService;
    @Autowired
    private PointsHoldMapper holdMapper;
    @Autowired
    private PointsLedgerMapper ledgerMapper;
    @Autowired
    private RateCardMapper rateCardMapper;
    @Autowired
    private UsageRecordMapper usageRecordMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkMapper workMapper;
    @Autowired
    private SessionMapper sessionMapper;
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    @Autowired
    private SystemSettingService systemSettingService;

    @BeforeEach
    void configureBilling() {
        RateCard fallback = rateCard(RateCard.DEFAULT_MODEL_ID, "10", "30");
        fallback.setCacheReadPoints(BigDecimal.ONE);
        fallback.setCacheWritePoints(BigDecimal.TEN);
        rateCardMapper.insertOrUpdate(fallback);
        rateCardMapper.deleteById("test-model");
        systemSettingService.set(SystemSetting.KEY_BILLING_EST_CACHE_READ_TOKENS, "90000");
        systemSettingService.set(SystemSetting.KEY_BILLING_EST_INPUT_TOKENS, "10000");
        systemSettingService.set(SystemSetting.KEY_BILLING_EST_OUTPUT_TOKENS, "20000");
    }

    private Long insertUser(String points) {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setPointsBalance(new BigDecimal(points));
        userMapper.insert(user);
        return user.getId();
    }

    private String insertSession(Long userId) {
        Work work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(userId);
        work.setName("测试项目");
        work.setSizeBytes(0L);
        work.setIsDefault(false);
        work.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        work.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        workMapper.insert(work);
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setWorkId(work.getId());
        session.setCurrentSegment(0);
        session.setStatus(Session.STATUS_ACTIVE);
        session.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        sessionMapper.insert(session);
        return session.getId();
    }

    /** 装配一条用量记录：input=uncached+cr+cw（inclusive 桶）。 */
    private UsageRecord usage(Long userId, String sessionId, String turnId,
                              long uncached, long cacheRead, long cacheWrite, long output) {
        UsageRecord record = new UsageRecord();
        record.setTurnId(turnId);
        record.setSessionId(sessionId);
        record.setUserId(userId);
        record.setModelId("test-model");
        record.setRequests(1);
        record.setInputTokens(uncached + cacheRead + cacheWrite);
        record.setCacheReadTokens(cacheRead);
        record.setCacheWriteTokens(cacheWrite);
        record.setOutputTokens(output);
        record.setStatus(UsageRecord.STATUS_FINISHED);
        record.setIsWake(false);
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return record;
    }

    private RateCard rateCard(String modelId, String inputPoints, String outputPoints) {
        RateCard row = new RateCard();
        row.setModelId(modelId);
        row.setInputPoints(new BigDecimal(inputPoints));
        row.setCacheReadPoints(BigDecimal.ZERO);
        row.setCacheWritePoints(BigDecimal.ZERO);
        row.setOutputPoints(new BigDecimal(outputPoints));
        row.setEnabled(true);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return row;
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + " 实际 " + actual);
    }

    private BigDecimal balance(Long userId) {
        return userMapper.selectById(userId).getPointsBalance();
    }

    private List<PointsLedger> ledgerOf(Long userId) {
        return ledgerMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getUserId, userId)
                .orderByAsc(PointsLedger::getId));
    }

    @Test
    @DisplayName("预扣：建 HELD 单 + HOLD 流水 + 按估算额扣款")
    void preDeductOk() {
        Long userId = insertUser("10");
        String turnId = String.valueOf(idGenerator.nextId());

        billingService.preDeduct(userId, turnId);

        PointsHold hold = holdMapper.selectById(turnId);
        assertNotNull(hold);
        assertEquals(PointsHold.STATUS_HELD, hold.getStatus());
        assertAmount(EST_HOLD.toPlainString(), hold.getAmount());
        assertAmount("9.21", balance(userId));
        List<PointsLedger> ledger = ledgerOf(userId);
        assertEquals(1, ledger.size());
        assertEquals(PointsLedger.TYPE_HOLD, ledger.get(0).getType());
        assertEquals(turnId, ledger.get(0).getRefId());
        assertAmount("-0.79", ledger.get(0).getChangeAmount());
        assertAmount("9.21", ledger.get(0).getBalanceAfter());
    }

    @Test
    @DisplayName("预扣：余额不足抛 409，hold/流水/余额零副作用")
    void preDeductInsufficient() {
        Long userId = insertUser("0.5");
        String turnId = String.valueOf(idGenerator.nextId());

        BizException e = assertThrows(BizException.class, () -> billingService.preDeduct(userId, turnId));
        assertEquals(409, e.getStatus());
        assertNull(holdMapper.selectById(turnId));
        assertEquals(0, ledgerOf(userId).size());
        assertAmount("0.5", balance(userId));
    }

    @Test
    @DisplayName("结算多退：实际成本低于预扣，差额退回，hold → SETTLED")
    void settleRefund() {
        Long userId = insertUser("10");
        String sessionId = insertSession(userId);
        String turnId = String.valueOf(idGenerator.nextId());
        billingService.preDeduct(userId, turnId);

        // 成本 = (1000×10 + 100×30) / 1e6 = 0.013（模型未注册，按兜底卡）
        billingService.recordUsageAndSettle(usage(userId, sessionId, turnId, 1000, 0, 0, 100));

        assertEquals(PointsHold.STATUS_SETTLED, holdMapper.selectById(turnId).getStatus());
        // 10 - 0.79 + (0.79 - 0.013) = 9.987
        assertAmount("9.987", balance(userId));
        List<PointsLedger> ledger = ledgerOf(userId);
        assertEquals(2, ledger.size());
        assertEquals(PointsLedger.TYPE_SETTLE, ledger.get(1).getType());
        assertAmount("0.777", ledger.get(1).getChangeAmount());
    }

    @Test
    @DisplayName("结算少补：实际成本高于预扣，允许扣成负余额（欠费）")
    void settleExtraChargeGoesNegative() {
        Long userId = insertUser("10");
        String sessionId = insertSession(userId);
        String turnId = String.valueOf(idGenerator.nextId());
        billingService.preDeduct(userId, turnId);

        // 成本 = (1e6×10 + 1e6×30) / 1e6 = 40
        billingService.recordUsageAndSettle(usage(userId, sessionId, turnId, 1_000_000, 0, 0, 1_000_000));

        // 9.21 - (40 - 0.79) = -30
        assertAmount("-30", balance(userId));
        List<PointsLedger> ledger = ledgerOf(userId);
        assertEquals(2, ledger.size());
        assertAmount("-39.21", ledger.get(1).getChangeAmount());
    }

    @Test
    @DisplayName("无预扣直接结算（wake 轮路径）：按实际成本 DIRECT 扣款")
    void directSettleWithoutHold() {
        Long userId = insertUser("10");
        String sessionId = insertSession(userId);
        String turnId = "wake-test" + UUID.randomUUID().toString().substring(0, 6);

        // 成本 = (2000×10 + 500×30) / 1e6 = 0.035
        billingService.recordUsageAndSettle(usage(userId, sessionId, turnId, 2000, 0, 0, 500));

        assertNull(holdMapper.selectById(turnId));
        assertAmount("9.965", balance(userId));
        List<PointsLedger> ledger = ledgerOf(userId);
        assertEquals(1, ledger.size());
        assertEquals(PointsLedger.TYPE_DIRECT, ledger.get(0).getType());
    }

    @Test
    @DisplayName("释放：预扣全额退回，hold → RELEASED；重复释放无副作用")
    void releaseRefundsFully() {
        Long userId = insertUser("10");
        String turnId = String.valueOf(idGenerator.nextId());
        billingService.preDeduct(userId, turnId);

        billingService.releaseHold(turnId);

        assertEquals(PointsHold.STATUS_RELEASED, holdMapper.selectById(turnId).getStatus());
        assertAmount("10", balance(userId));
        assertEquals(2, ledgerOf(userId).size());

        billingService.releaseHold(turnId);  // 已收口，静默跳过
        assertAmount("10", balance(userId));
        assertEquals(2, ledgerOf(userId).size());
    }

    @Test
    @DisplayName("幂等：同一 turn 的用量事件重复消费，用量与结算整单跳过")
    void duplicateLifecycleSkipped() {
        Long userId = insertUser("10");
        String sessionId = insertSession(userId);
        String turnId = String.valueOf(idGenerator.nextId());
        billingService.preDeduct(userId, turnId);
        UsageRecord record = usage(userId, sessionId, turnId, 1000, 0, 0, 100);

        billingService.recordUsageAndSettle(record);
        BigDecimal afterFirst = balance(userId);
        billingService.recordUsageAndSettle(record);

        assertAmount(afterFirst.toPlainString(), balance(userId));
        assertEquals(2, ledgerOf(userId).size());  // HOLD + SETTLE，没有第三条
    }

    @Test
    @DisplayName("收口互斥：已 SETTLED 的预扣再 release 无效，已 RELEASED 再结算只走 DIRECT 判定")
    void settleReleaseMutex() {
        Long userId = insertUser("10");
        String sessionId = insertSession(userId);
        String turnId = String.valueOf(idGenerator.nextId());
        billingService.preDeduct(userId, turnId);
        billingService.recordUsageAndSettle(usage(userId, sessionId, turnId, 1000, 0, 0, 100));
        BigDecimal settled = balance(userId);

        billingService.releaseHold(turnId);  // 已结算，不得退回
        assertAmount(settled.toPlainString(), balance(userId));
        assertEquals(2, ledgerOf(userId).size());
    }

    @Test
    @DisplayName("调账：正向入账带操作人与事由；调成负余额被拒 409")
    void adjustRules() {
        Long operator = insertUser("1000");
        Long target = insertUser("10");

        billingService.adjust(operator, target, new BigDecimal("100"), "开站赠送");

        assertAmount("110", balance(target));
        PointsLedger row = ledgerOf(target).get(0);
        assertEquals(PointsLedger.TYPE_ADJUST, row.getType());
        assertEquals(operator, row.getOperatorId());
        assertEquals("开站赠送", row.getReason());
        assertNotNull(row.getRefId());

        BizException e = assertThrows(BizException.class,
                () -> billingService.adjust(operator, target, new BigDecimal("-200"), "扣减"));
        assertEquals(409, e.getStatus());
        assertAmount("110", balance(target));

        assertThrows(BizException.class,
                () -> billingService.adjust(operator, target, BigDecimal.ZERO, "无意义"));
    }

    @Test
    @DisplayName("多模型用量：按各自模型 ID 资费结算")
    void settlesMultipleModelsByTheirOwnRates() {
        Long userId = insertUser("10");
        String sessionId = insertSession(userId);
        String turnId = String.valueOf(idGenerator.nextId());
        billingService.preDeduct(userId, turnId);
        rateCardMapper.insert(rateCard("test/model-a", "100", "200"));
        rateCardMapper.insert(rateCard("test/model-b", "300", "400"));
        UsageRecord first = usage(userId, sessionId, turnId, 1_000, 0, 0, 1_000);
        first.setModelId("test/model-a");
        UsageRecord second = usage(userId, sessionId, turnId, 1_000, 0, 0, 1_000);
        second.setModelId("test/model-b");

        billingService.recordUsageAndSettle(List.of(first, second));

        assertAmount("9", balance(userId));
        assertEquals(2, usageRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UsageRecord>()
                        .eq(UsageRecord::getTurnId, turnId)).size());
    }
}
