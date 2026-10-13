package com.spring.gateway.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.common.ResourceLimits;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.dto.AdminRedeemCreateRequest;
import com.spring.gateway.entity.PointsLedger;
import com.spring.gateway.entity.RedeemCode;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.PointsLedgerMapper;
import com.spring.gateway.mapper.RedeemCodeMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.service.AdminUserService;
import com.spring.gateway.service.BillingService;
import com.spring.gateway.service.RedeemService;
import com.spring.gateway.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 主动额度边界与事务防御测试。
 *
 * @author hanbing
 * @since 2026-10-13
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ResourceLimitsTests {
    @Autowired private MockMvc mvc;
    @Autowired private JwtUtils jwt;
    @Autowired private SnowflakeIdGenerator ids;
    @Autowired private UserMapper users;
    @Autowired private RedeemCodeMapper codes;
    @Autowired private PointsLedgerMapper ledger;
    @Autowired private BillingService billing;
    @Autowired private AdminUserService adminUsers;
    @Autowired private RedeemService redeem;
    @Autowired private PlatformTransactionManager transactionManager;
    private User owner;
    private User target;
    private String bearer;

    @BeforeEach
    void fixture() {
        owner = user(User.ROLE_OWNER);
        target = user(User.ROLE_USER);
        bearer = "Bearer " + jwt.createToken(owner.getId(), owner.getUsername());
    }

    private User user(String role) {
        User row = new User();
        row.setId(ids.nextId());
        row.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        row.setPasswordHash("test-only");
        row.setRole(role);
        row.setStatus(User.STATUS_ACTIVE);
        row.setPointsBalance(new BigDecimal("100"));
        row.setQuotaBytes(0L);
        users.insert(row);
        return row;
    }

    private void invalid(String path, String body) throws Exception {
        mvc.perform(post(path).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    private String userPath(String action) {
        return "/api/admin/users/" + target.getId() + "/" + action;
    }

    @Test
    void rejectsInvalidAdjustmentAmountsWithoutWritingLedger() throws Exception {
        for (String amount : new String[]{"0", "1000000.000001", "-1000000.000001", "0.0000001",
                "1e100", "\"1e-7\"", "null", "\"NaN\""}) {
            invalid(userPath("points"), "{\"delta\":" + amount + ",\"reason\":\"测试\"}");
        }
        invalid(userPath("points"), "{\"delta\":1,\"reason\":\"   \"}");
        assertEquals(new BigDecimal("100.000000"), billing.balanceOf(target.getId()));
        assertEquals(0, ledger.selectCount(new LambdaQueryWrapper<PointsLedger>().eq(PointsLedger::getUserId, target.getId())));
    }

    @Test
    void acceptsExactSmallestAndLargestAdjustment() throws Exception {
        for (String amount : new String[]{"\"0.000001\"", "1000000", "-1000000"}) {
            mvc.perform(post(userPath("points")).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delta\":" + amount + ",\"reason\":\"测试\"}"))
                    .andExpect(status().isOk());
        }
        assertEquals(new BigDecimal("100.000001"), billing.balanceOf(target.getId()));
    }

    @Test
    void rejectsOversizedFractionalAndMissingQuotas() throws Exception {
        for (String action : new String[]{"quota", "work-quota"}) {
            for (String amount : new String[]{"-1", "1099511627777", "9223372036854775807", "1.5", "1.0", "\"1.5\""}) {
                invalid(userPath(action), "{\"quotaBytes\":" + amount + "}");
            }
        }
        invalid(userPath("quota"), "{\"quotaBytes\":null}");
        assertEquals(0L, users.selectById(target.getId()).getQuotaBytes());
    }

    @Test
    void acceptsQuotaBoundsAndWorkInheritance() throws Exception {
        for (long value : new long[]{0, ResourceLimits.MAX_QUOTA_BYTES}) {
            mvc.perform(post(userPath("quota")).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"quotaBytes\":" + value + "}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post(userPath("work-quota")).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quotaBytes\":null}"))
                .andExpect(status().isOk());
        assertNull(users.selectById(target.getId()).getWorkQuotaBytes());
    }

    @Test
    void rejectsRedeemFacePrecisionIntegerTruncationAndExtremeExpiry() throws Exception {
        String[] bodies = {
                "{\"count\":1,\"points\":1000000.000001}",
                "{\"count\":1,\"points\":0.0000001}",
                "{\"count\":1,\"points\":-1}",
                "{\"count\":1.5,\"points\":1}",
                "{\"count\":101,\"points\":1}",
                "{\"count\":1,\"points\":1,\"storage_mb\":1.5}",
                "{\"count\":1,\"points\":1,\"work_quota_mb\":1048577}",
                "{\"count\":1,\"points\":1,\"storage_mb\":9223372036854775807}",
                "{\"count\":1,\"points\":1,\"expires_in_hours\":87601}",
                "{\"count\":1,\"points\":1,\"expires_in_hours\":1.5}",
                "{\"count\":1,\"points\":0,\"storage_mb\":0}"
        };
        for (String body : bodies) invalid("/api/admin/redeem-codes", body);
        assertEquals(0, codes.selectCount(new LambdaQueryWrapper<RedeemCode>().eq(RedeemCode::getCreatedBy, owner.getId())));
    }

    @Test
    void rejectsInvalidRateAndSettings() throws Exception {
        for (String amount : new String[]{"1000001", "0.0000001", "-1"}) {
            mvc.perform(put("/api/admin/rate-cards").header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"modelId\":\"__default__\","
                                    + "\"inputPoints\":" + amount + ",\"cacheReadPoints\":0,\"cacheWritePoints\":0,\"outputPoints\":0}"))
                    .andExpect(status().isBadRequest());
        }
        for (String value : new String[]{"0", "1099511627777", "1048576.5"}) {
            invalid("/api/admin/settings/work-max-bytes", "{\"bytes\":" + value + "}");
        }
        for (String value : new String[]{"10000001", "1.5", "-1"}) {
            invalid("/api/admin/settings/billing-est", "{\"cacheReadTokens\":0,\"inputTokens\":" + value + ",\"outputTokens\":0}");
        }
    }

    @Test
    void servicesRejectInvalidValuesWithoutHttpValidation() {
        assertThrows(BizException.class, () -> billing.adjust(owner.getId(), target.getId(), new BigDecimal("0.0000001"), "测试"));
        assertThrows(BizException.class, () -> billing.adjust(owner.getId(), target.getId(), BigDecimal.ONE, " "));
        assertThrows(BizException.class, () -> adminUsers.setQuota(User.ROLE_OWNER, target.getId(), Long.MAX_VALUE));
        assertThrows(BizException.class, () -> adminUsers.setWorkQuota(User.ROLE_OWNER, target.getId(), -1L));
        assertThrows(BizException.class, () -> redeem.generate(owner.getId(),
                new AdminRedeemCreateRequest(1, BigDecimal.ONE, Long.MAX_VALUE, null, null)));
        assertThrows(BizException.class, () -> redeem.generate(owner.getId(),
                new AdminRedeemCreateRequest(101, BigDecimal.ONE, null, null, null)));
        assertThrows(BizException.class, () -> redeem.generate(owner.getId(),
                new AdminRedeemCreateRequest(1, BigDecimal.ONE, null, null, Long.MAX_VALUE)));
    }

    @Test
    void acceptsRedeemAndSettingsUpperBounds() throws Exception {
        mvc.perform(post("/api/admin/redeem-codes").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"points\":\"1000000.000000\",\"storage_mb\":1048576,"
                                + "\"work_quota_mb\":1048576,\"expires_in_hours\":87600}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/settings/work-max-bytes").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bytes\":1099511627776}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/settings/billing-est").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cacheReadTokens\":10000000,\"inputTokens\":0,\"outputTokens\":10000000}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/rate-cards").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"modelId\":\"__default__\","
                                + "\"inputPoints\":\"1000000.000000\",\"cacheReadPoints\":0,\"cacheWritePoints\":0,\"outputPoints\":0.000001}"))
                .andExpect(status().isOk());
    }

    @Test
    void activeTopUpCannotExceedBalanceCap() throws Exception {
        users.updateBalance(target.getId(), ResourceLimits.MAX_BALANCE);
        mvc.perform(post(userPath("points")).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":0.000001,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict());
        assertEquals(ResourceLimits.MAX_BALANCE.setScale(6), billing.balanceOf(target.getId()));
    }

    @Test
    void exposesLimitsToAdminsButNotOrdinaryUsers() throws Exception {
        mvc.perform(get("/api/admin/resource-limits").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pointsScale").value(6))
                .andExpect(jsonPath("$.data.quotaMbMax").value(1048576));
        mvc.perform(get("/api/admin/resource-limits").header("Authorization",
                        "Bearer " + jwt.createToken(target.getId(), target.getUsername())))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void exceededRedemptionRollsBackAllAwardsAndClaim() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        long[] setup = tx.execute(status -> {
            User actor = user(User.ROLE_OWNER);
            User recipient = user(User.ROLE_USER);
            recipient.setQuotaBytes(ResourceLimits.MAX_QUOTA_BYTES);
            users.updateById(recipient);
            RedeemCode code = redeem.generate(actor.getId(), new AdminRedeemCreateRequest(1,
                    BigDecimal.ONE, 1L, 1L, null)).getFirst();
            return new long[]{actor.getId(), recipient.getId(), code.getId()};
        });
        try {
            String code = codes.selectById(setup[2]).getCode();
            assertEquals(409, assertThrows(BizException.class, () -> redeem.redeem(setup[1], code)).getStatus());
            User after = users.selectById(setup[1]);
            assertEquals(new BigDecimal("100.000000"), after.getPointsBalance());
            assertEquals(ResourceLimits.MAX_QUOTA_BYTES, after.getQuotaBytes());
            assertNull(after.getWorkQuotaBytes());
            assertEquals(RedeemCode.STATUS_ACTIVE, codes.selectById(setup[2]).getStatus());
            assertEquals(0, ledger.selectCount(new LambdaQueryWrapper<PointsLedger>().eq(PointsLedger::getUserId, setup[1])));
            after.setQuotaBytes(0L);
            after.setWorkQuotaBytes(ResourceLimits.MAX_QUOTA_BYTES);
            users.updateById(after);
            assertThrows(BizException.class, () -> redeem.redeem(setup[1], code));
            assertEquals(0L, users.selectById(setup[1]).getQuotaBytes());
            after.setWorkQuotaBytes(0L);
            users.updateById(after);
            users.updateBalance(setup[1], ResourceLimits.MAX_BALANCE);
            assertThrows(BizException.class, () -> redeem.redeem(setup[1], code));
            assertEquals(RedeemCode.STATUS_ACTIVE, codes.selectById(setup[2]).getStatus());
        } finally {
            tx.executeWithoutResult(status -> {
                ledger.delete(new LambdaQueryWrapper<PointsLedger>().eq(PointsLedger::getUserId, setup[1]));
                codes.deleteById(setup[2]);
                users.deleteById(setup[1]);
                users.deleteById(setup[0]);
                users.deleteById(target.getId());
                users.deleteById(owner.getId());
            });
        }
    }
}
