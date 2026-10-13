package com.spring.gateway.controller;

import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.dto.AdminRedeemCreateRequest;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.entity.PointsLedger;
import com.spring.gateway.entity.Session;
import com.spring.gateway.entity.UsageRecord;
import com.spring.gateway.entity.User;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.PointsLedgerMapper;
import com.spring.gateway.mapper.SessionMapper;
import com.spring.gateway.mapper.UsageRecordMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.mapper.WorkMapper;
import com.spring.gateway.service.RedeemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理后台四个列表接口的分页行为：页码钳制、总数正确、末页部分行。
 *
 * @author hanbing
 * @since 2026-10-11
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminPaginationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    @Autowired
    private PointsLedgerMapper pointsLedgerMapper;
    @Autowired
    private UsageRecordMapper usageRecordMapper;
    @Autowired
    private WorkMapper workMapper;
    @Autowired
    private SessionMapper sessionMapper;
    @Autowired
    private RedeemService redeemService;

    /** 插入一个指定角色的用户并签发 token。 */
    private String tokenFor(String role) {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setRole(role);
        user.setStatus(User.STATUS_ACTIVE);
        userMapper.insert(user);
        return jwtUtils.createToken(user.getId(), user.getUsername());
    }

    private Long newUserId() {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setRole(User.ROLE_USER);
        user.setStatus(User.STATUS_ACTIVE);
        userMapper.insert(user);
        return user.getId();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    @DisplayName("用户分页：size 生效、total 含存量、页码越界钳回第 1 页、options 全量")
    void usersPage() throws Exception {
        String token = tokenFor(User.ROLE_ADMIN);
        for (int i = 0; i < 3; i++) {
            newUserId();
        }

        mockMvc.perform(get("/api/admin/users?page=1&size=2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(3)));

        // page=0 钳回 1；size=999 钳到 100
        mockMvc.perform(get("/api/admin/users?page=0&size=999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(100));

        // options 返回全量（不分页），含刚建的用户
        mockMvc.perform(get("/api/admin/users/options")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(4)));
    }

    @Test
    @DisplayName("用量分页：total 为用户×模型分组数，size=1 时每页一行")
    void usagePage() throws Exception {
        String token = tokenFor(User.ROLE_ADMIN);
        Long uid = newUserId();

        Work work = new Work();
        work.setId("w" + idGenerator.nextId());
        work.setUserId(uid);
        work.setName("t");
        work.setSizeBytes(0L);
        work.setIsDefault(false);
        work.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        work.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        workMapper.insert(work);
        Session session = new Session();
        session.setId("s" + idGenerator.nextId());
        session.setUserId(uid);
        session.setWorkId(work.getId());
        session.setTitle("t");
        session.setCurrentSegment(0);
        session.setStatus("active");
        session.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        session.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        sessionMapper.insert(session);

        for (int i = 0; i < 3; i++) {
            UsageRecord r = new UsageRecord();
            r.setTurnId("t" + idGenerator.nextId());
            r.setSessionId(session.getId());
            r.setUserId(uid);
            r.setModelId("m" + (i % 2));  // 两个模型 → 两个分组
            r.setRequests(1);
            r.setInputTokens(100L);
            r.setCacheReadTokens(0L);
            r.setCacheWriteTokens(0L);
            r.setOutputTokens(10L);
            r.setStatus(UsageRecord.STATUS_FINISHED);
            r.setIsWake(false);
            r.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            usageRecordMapper.insert(r);
        }

        mockMvc.perform(get("/api/admin/usage?userId=" + uid + "&page=1&size=1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(1));

        mockMvc.perform(get("/api/admin/usage?userId=" + uid + "&page=2&size=1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1));
    }

    @Test
    @DisplayName("流水分页：25 条按 size=10 翻页，末页 5 条，第 4 页为空")
    void ledgerPage() throws Exception {
        String token = tokenFor(User.ROLE_ADMIN);
        Long uid = newUserId();
        for (int i = 0; i < 25; i++) {
            PointsLedger row = new PointsLedger();
            row.setId(idGenerator.nextId());
            row.setUserId(uid);
            row.setChangeAmount(BigDecimal.ONE);
            row.setBalanceAfter(BigDecimal.valueOf(i + 1L));
            row.setType(PointsLedger.TYPE_ADJUST);
            row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            pointsLedgerMapper.insert(row);
        }

        mockMvc.perform(get("/api/admin/points/ledger?userId=" + uid + "&page=1&size=10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.list.length()").value(10));

        mockMvc.perform(get("/api/admin/points/ledger?userId=" + uid + "&page=3&size=10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(5));

        mockMvc.perform(get("/api/admin/points/ledger?userId=" + uid + "&page=4&size=10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(0));
    }

    @Test
    @DisplayName("兑换码分页：生成 25 个后 total 相应增加，size=10 时每页 10 条")
    void redeemPage() throws Exception {
        Long ownerId = newUserId();
        User owner = userMapper.selectById(ownerId);
        owner.setRole(User.ROLE_OWNER);
        userMapper.updateById(owner);
        String token = jwtUtils.createToken(ownerId, owner.getUsername());
        long before = redeemService.page(null, new PageQuery(1, 1)).total();

        redeemService.generate(ownerId,
                new AdminRedeemCreateRequest(25, BigDecimal.ONE, null, null, null));

        mockMvc.perform(get("/api/admin/redeem-codes?page=1&size=10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(before + 25))
                .andExpect(jsonPath("$.data.list.length()").value(10));
    }
}
