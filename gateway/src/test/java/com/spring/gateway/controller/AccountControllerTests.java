package com.spring.gateway.controller;

import com.spring.gateway.service.AuthSessionService;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账户接口集成测试：鉴权（无 token 401）与资料查询。
 *
 * @author hanbing
 * @since 2026-10-10
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthSessionService jwtUtils;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    @Autowired
    private SystemSettingService systemSettingService;

    /** 插入一个普通用户并签发 token，返回 [userId, token]。 */
    private Object[] insertUserWithToken(String email) {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setEmail(email);
        user.setRole(User.ROLE_USER);
        user.setStatus(User.STATUS_ACTIVE);
        userMapper.insert(user);
        return new Object[]{user.getId(), jwtUtils.createSession(user.getId()).token()};
    }

    @Test
    @DisplayName("无 token 访问账户接口返回 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/account/send-bind-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/account/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"x\",\"newPassword\":\"secret123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("me 返回当前用户名与绑定邮箱；改密双模式（关闭邮箱走旧密码，开启走验证码）")
    void meAndValidation() throws Exception {
        String email = "t_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        Object[] pair = insertUserWithToken(email);
        String token = (String) pair[1];

        mockMvc.perform(get("/api/account/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email));

        // 显式控制开关（不依赖库里的实时配置，事务结束回滚）。
        // 关闭邮箱功能：缺旧密码 → 旧密码错误；新密码太短 → 400（test-only 非合法 BCrypt，matches 必 false）
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "false");
        mockMvc.perform(post("/api/account/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"secret123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("旧密码错误"));
        mockMvc.perform(post("/api/account/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"x\",\"newPassword\":\"123\"}"))
                .andExpect(status().isBadRequest());

        // 开启邮箱功能：没带验证码 → 400 验证码错误（该用户有绑定邮箱，越过前提检查）
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true");
        mockMvc.perform(post("/api/account/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"secret123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("验证码错误或已过期"));
    }
}
