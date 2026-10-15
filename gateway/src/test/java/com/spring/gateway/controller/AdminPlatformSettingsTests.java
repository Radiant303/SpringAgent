package com.spring.gateway.controller;

import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.SystemSettingMapper;
import com.spring.gateway.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台对外地址设置的域名校验测试。
 *
 * @author hanbing
 * @since 2026-10-16
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminPlatformSettingsTests {
    @Autowired private MockMvc mvc;
    @Autowired private JwtUtils jwt;
    @Autowired private SnowflakeIdGenerator ids;
    @Autowired private UserMapper users;
    @Autowired private SystemSettingMapper settings;
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
        bearer = "Bearer " + jwt.createToken(owner.getId(), owner.getUsername());
    }

    private void save(String domain, boolean https, int status) throws Exception {
        mvc.perform(post("/api/admin/settings/platform").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"" + domain + "\",\"https\":" + https + "}"))
                .andExpect(status().is(status));
    }

    @Test
    void acceptsHostnameWithOptionalPort() throws Exception {
        save("files.example.com", true, 200);
        assertEquals("files.example.com",
                settings.selectById(SystemSetting.KEY_PLATFORM_DOMAIN).getSettingValue());
        assertEquals("true", settings.selectById(SystemSetting.KEY_PLATFORM_HTTPS).getSettingValue());
        save("example.com:8080", false, 200);
        assertEquals("example.com:8080",
                settings.selectById(SystemSetting.KEY_PLATFORM_DOMAIN).getSettingValue());
        assertEquals("false", settings.selectById(SystemSetting.KEY_PLATFORM_HTTPS).getSettingValue());
    }

    @Test
    void rejectsSchemePathAndSpace() throws Exception {
        save("https://example.com", false, 400);
        save("example.com/files", false, 400);
        save("exa mple.com", false, 400);
    }

    @Test
    void emptyDomainClearsConfig() throws Exception {
        save("files.example.com", true, 200);
        save("", false, 200);
        assertEquals("", settings.selectById(SystemSetting.KEY_PLATFORM_DOMAIN).getSettingValue());
    }
}
