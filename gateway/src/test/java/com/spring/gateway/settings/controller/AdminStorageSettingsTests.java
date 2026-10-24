package com.spring.gateway.settings.controller;

import com.spring.gateway.identity.service.AuthSessionService;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.settings.mapper.SystemSettingMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.storage.infrastructure.ReconcileScheduler;
import com.spring.gateway.settings.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存储设置对账 cron 下限校验测试。
 *
 * @author hanbing
 * @since 2026-10-15
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminStorageSettingsTests {
    @Autowired private MockMvc mvc;
    @Autowired private AuthSessionService jwt;
    @Autowired private SnowflakeIdGenerator ids;
    @Autowired private UserMapper users;
    @Autowired private SystemSettingMapper settings;
    @MockitoBean private ReconcileScheduler reconcileScheduler;
    private String bearer;

    @BeforeEach
    void fixture() {
        User owner = new User();
        owner.setId(ids.nextId());
        owner.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        owner.setPasswordHash("test-only");
        owner.setRole(User.ROLE_OWNER);
        owner.setStatus(User.STATUS_ACTIVE);
        owner.setPointsBalance(new BigDecimal("100"));
        owner.setQuotaBytes(0L);
        users.insert(owner);
        bearer = "Bearer " + jwt.createSession(owner.getId()).token();
    }

    private void save(String cron, int status) throws Exception {
        mvc.perform(post("/api/admin/settings/storage").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"local\",\"reconcileCron\":\"" + cron + "\"}"))
                .andExpect(status().is(status));
    }

    @Test
    void rejectsCronWithSubLimitIntervals() throws Exception {
        for (String cron : new String[]{"0 */5 * * * *", "*/30 * * * * *", "0 0,29 * * * *"}) {
            save(cron, 400);
        }
    }

    @Test
    void acceptsCronAtOrAboveLimit() throws Exception {
        save("0 */30 * * * *", 200);
        save("0 0 * * * *", 200);
    }

    @Test
    void emptyCronFallsBackToDefault() throws Exception {
        save("", 200);
        SystemSetting row = settings.selectById(SystemSetting.KEY_STORAGE_RECONCILE_CRON);
        assertEquals(SystemSettingService.DEFAULT_RECONCILE_CRON, row.getSettingValue());
    }
}
