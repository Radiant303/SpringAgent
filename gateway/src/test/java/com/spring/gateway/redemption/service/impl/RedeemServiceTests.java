package com.spring.gateway.redemption.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.redemption.dto.AdminRedeemCreateRequest;
import com.spring.gateway.redemption.dto.RedeemResultView;
import com.spring.gateway.billing.entity.PointsLedger;
import com.spring.gateway.redemption.entity.RedeemCode;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.billing.mapper.PointsLedgerMapper;
import com.spring.gateway.redemption.mapper.RedeemCodeMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.redemption.service.RedeemService;
import com.spring.gateway.settings.service.SystemSettingService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兑换码集成测试：生成-兑换-作废全链路。
 *
 * <p>连真实 MySQL，@Transactional 保证用例结束后回滚。
 *
 * @author hanbing
 * @since 2026-10-06
 */
@SpringBootTest
@Transactional
class RedeemServiceTests {

    /** 初始存储配额（字节），避免依赖迁移播种的默认值。 */
    private static final long INIT_QUOTA = 1_000_000L;

    @Autowired
    private RedeemService redeemService;
    @Autowired
    private SystemSettingService systemSettingService;
    @Autowired
    private RedeemCodeMapper redeemCodeMapper;
    @Autowired
    private PointsLedgerMapper ledgerMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private Long insertUser(String points) {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setQuotaBytes(INIT_QUOTA);
        user.setPointsBalance(new BigDecimal(points));
        userMapper.insert(user);
        return user.getId();
    }

    private RedeemCode generateOne(Long ownerId, String points, Long storageMb,
                                   Long workQuotaMb, Long expiresInHours) {
        return redeemService.generate(ownerId, new AdminRedeemCreateRequest(
                1, points == null ? null : new BigDecimal(points),
                storageMb, workQuotaMb, expiresInHours)).get(0);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + " 实际 " + actual);
    }

    private BizException redeemFails(Long userId, String code) {
        return assertThrows(BizException.class, () -> redeemService.redeem(userId, code));
    }

    @Test
    @DisplayName("生成：三项面值全为 0 被拒（400）")
    void generateAllZeroRejected() {
        Long ownerId = insertUser("1000");
        BizException e = assertThrows(BizException.class, () -> redeemService.generate(
                ownerId, new AdminRedeemCreateRequest(1, BigDecimal.ZERO, 0L, 0L, null)));
        assertEquals(400, e.getStatus());
    }

    @Test
    @DisplayName("生成：批量 3 个，码为 16 位 Crockford 规范形且唯一，面值与失效时间正确")
    void generateBatchFormatAndUniqueness() {
        Long ownerId = insertUser("1000");
        List<RedeemCode> rows = redeemService.generate(ownerId,
                new AdminRedeemCreateRequest(3, new BigDecimal("5.5"), 10L, 20L, 24L));
        assertEquals(3, rows.size());
        assertEquals(3, rows.stream().map(RedeemCode::getCode).distinct().count());
        for (RedeemCode row : rows) {
            assertTrue(row.getCode().matches("[0-9A-HJKMNP-TV-Z]{16}"), "码格式: " + row.getCode());
            assertEquals(RedeemCode.STATUS_ACTIVE, row.getStatus());
            assertAmount("5.5", row.getPointsAmount());
            assertEquals(10L * 1024 * 1024, row.getStorageDeltaBytes());
            assertEquals(20L * 1024 * 1024, row.getWorkQuotaDeltaBytes());
            assertNotNull(row.getExpiresAt());
            assertEquals(ownerId, row.getCreatedBy());
            assertNull(row.getDeletedAt());
        }
    }

    @Test
    @DisplayName("兑换：三项全含 → 余额+REDEEM 流水+两配额增量+软删除+记录使用者")
    void redeemGrantsAll() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("10");
        RedeemCode row = generateOne(ownerId, "25.5", 10L, 20L, 24L);

        RedeemResultView result = redeemService.redeem(userId, row.getCode());

        assertAmount("25.5", result.grantedPoints());
        assertEquals(10L * 1024 * 1024, result.grantedStorageBytes());
        assertEquals(20L * 1024 * 1024, result.grantedWorkQuotaBytes());
        assertAmount("35.5", result.balanceAfter());
        assertAmount("35.5", userMapper.selectById(userId).getPointsBalance());

        List<PointsLedger> ledger = ledgerOf(userId);
        assertEquals(1, ledger.size());
        assertEquals(PointsLedger.TYPE_REDEEM, ledger.get(0).getType());
        assertEquals(String.valueOf(row.getId()), ledger.get(0).getRefId());
        assertAmount("35.5", ledger.get(0).getBalanceAfter());

        User after = userMapper.selectById(userId);
        assertEquals(INIT_QUOTA + 10L * 1024 * 1024, after.getQuotaBytes());
        // work_quota_bytes 原为 NULL（跟随全局）→ 以全局基线落显式覆盖值
        assertEquals(systemSettingService.getWorkMaxBytes() + 20L * 1024 * 1024,
                after.getWorkQuotaBytes());

        RedeemCode used = redeemCodeMapper.selectById(row.getId());
        assertEquals(RedeemCode.STATUS_REDEEMED, used.getStatus());
        assertEquals(userId, used.getRedeemedBy());
        assertNotNull(used.getRedeemedAt());
        assertNotNull(used.getDeletedAt());
    }

    @Test
    @DisplayName("兑换：小写+连字符输入被规范化")
    void redeemNormalizesInput() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("0");
        RedeemCode row = generateOne(ownerId, "5", null, null, null);
        String fancy = row.getCode().toLowerCase().replaceAll("(.{4})(?=.)", "$1-");

        RedeemResultView result = redeemService.redeem(userId, fancy);
        assertAmount("5", result.grantedPoints());
        assertEquals(RedeemCode.STATUS_REDEEMED, redeemCodeMapper.selectById(row.getId()).getStatus());
    }

    @Test
    @DisplayName("兑换：重复兑换 409，余额不二次入账")
    void redeemTwiceRejected() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("10");
        RedeemCode row = generateOne(ownerId, "5", null, null, null);
        redeemService.redeem(userId, row.getCode());

        BizException e = redeemFails(userId, row.getCode());
        assertEquals(409, e.getStatus());
        assertEquals("兑换码已被使用", e.getMessage());
        assertAmount("15", userMapper.selectById(userId).getPointsBalance());
        assertEquals(1, ledgerOf(userId).size());
    }

    @Test
    @DisplayName("兑换：过期码 409")
    void redeemExpiredRejected() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("0");
        RedeemCode row = generateOne(ownerId, "5", null, null, 1L);
        row.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        redeemCodeMapper.updateById(row);

        BizException e = redeemFails(userId, row.getCode());
        assertEquals(409, e.getStatus());
        assertEquals("兑换码已过期", e.getMessage());
        assertEquals(RedeemCode.STATUS_ACTIVE, redeemCodeMapper.selectById(row.getId()).getStatus());
    }

    @Test
    @DisplayName("兑换：作废码 409；不存在的码 404")
    void redeemRevokedAndUnknown() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("0");
        RedeemCode row = generateOne(ownerId, "5", null, null, null);
        redeemService.revoke(row.getId());

        BizException e1 = redeemFails(userId, row.getCode());
        assertEquals(409, e1.getStatus());
        assertEquals("兑换码已作废", e1.getMessage());

        BizException e2 = redeemFails(userId, "ABCD1234EFGH5678");
        assertEquals(404, e2.getStatus());
    }

    @Test
    @DisplayName("作废：非待使用码 409；不存在 404")
    void revokeGuards() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("0");
        RedeemCode row = generateOne(ownerId, "5", null, null, null);
        redeemService.redeem(userId, row.getCode());

        BizException e1 = assertThrows(BizException.class, () -> redeemService.revoke(row.getId()));
        assertEquals(409, e1.getStatus());
        BizException e2 = assertThrows(BizException.class, () -> redeemService.revoke(999999999L));
        assertEquals(404, e2.getStatus());
    }

    @Test
    @DisplayName("兑换：纯配额码不动余额、无流水；已有显式 work 上限时增量叠加")
    void redeemQuotaOnly() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("7");
        User user = userMapper.selectById(userId);
        user.setWorkQuotaBytes(5000L);
        userMapper.updateById(user);

        RedeemCode row = generateOne(ownerId, null, 1L, 1L, null);
        RedeemResultView result = redeemService.redeem(userId, row.getCode());

        assertAmount("0", result.grantedPoints());
        assertAmount("7", result.balanceAfter());
        assertAmount("7", userMapper.selectById(userId).getPointsBalance());
        assertEquals(0, ledgerOf(userId).size());

        User after = userMapper.selectById(userId);
        assertEquals(INIT_QUOTA + 1024 * 1024, after.getQuotaBytes());
        assertEquals(5000L + 1024 * 1024, after.getWorkQuotaBytes());
    }

    private List<PointsLedger> ledgerOf(Long userId) {
        return ledgerMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getUserId, userId));
    }

    @Test
    @DisplayName("兑换记录：我的记录按时间倒序，含纯配额兑换，不含他人")
    void myRedemptionsListed() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("0");
        RedeemCode c1 = generateOne(ownerId, "5", null, null, null);
        RedeemCode c2 = generateOne(ownerId, null, 1L, null, null);
        redeemService.redeem(userId, c1.getCode());
        redeemService.redeem(userId, c2.getCode());

        List<RedeemCode> mine = redeemService.myRedemptions(userId, 10);
        assertEquals(2, mine.size());
        assertEquals(c2.getId(), mine.get(0).getId());  // 后兑换的在前
        assertEquals(c1.getId(), mine.get(1).getId());
        assertEquals(0, redeemService.myRedemptions(ownerId, 10).size());
    }

    @Test
    @DisplayName("限流：连续失败 10 次后第 11 次被 429，有效码同样被挡")
    void redeemRateLimitedAfterTenFailures() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("0");
        RedeemCode valid = generateOne(ownerId, "5", null, null, null);

        // 10 次失败各自拿到真实失败原因（404）
        for (int i = 0; i < 10; i++) {
            BizException e = redeemFails(userId, "BAD" + i + "000000000000");
            assertEquals(404, e.getStatus(), "第 " + (i + 1) + " 次失败应正常透传");
        }
        // 桶空：第 11 次直接 429；有效码也不放行（不透传码状态）
        BizException blocked = redeemFails(userId, valid.getCode());
        assertEquals(429, blocked.getStatus());
        assertEquals(RedeemCode.STATUS_ACTIVE, redeemCodeMapper.selectById(valid.getId()).getStatus());

        String key = "redeem:fail:bucket:" + userId;
        assertEquals(Boolean.TRUE, redisTemplate.hasKey(key));
        redisTemplate.delete(key);  // 清理：限流桶在 Redis，不受 @Transactional 回滚
    }

    @Test
    @DisplayName("限流：兑换成功清零桶，之后重新获得完整 10 次额度")
    void redeemSuccessResetsFailBucket() {
        Long ownerId = insertUser("1000");
        Long userId = insertUser("0");
        RedeemCode valid = generateOne(ownerId, "5", null, null, null);
        String key = "redeem:fail:bucket:" + userId;

        for (int i = 0; i < 8; i++) {
            redeemFails(userId, "BAD" + i + "000000000000");
        }
        assertEquals(Boolean.TRUE, redisTemplate.hasKey(key));

        redeemService.redeem(userId, valid.getCode());
        // 成功即清零：key 删除，等价于满桶
        assertEquals(Boolean.FALSE, redisTemplate.hasKey(key));

        // 重新获得完整额度：再连续失败 10 次都正常透传，第 11 次才 429
        for (int i = 0; i < 10; i++) {
            assertEquals(404, redeemFails(userId, "BADX" + i + "00000000000").getStatus());
        }
        assertEquals(429, redeemFails(userId, "BADX100000000000").getStatus());
        redisTemplate.delete(key);
    }
}
