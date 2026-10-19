package com.spring.gateway.controller;

import com.spring.gateway.entity.SystemSetting;
import com.spring.gateway.service.QqMailSender;
import com.spring.gateway.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口集成测试。
 * QqMailSender 被替换为 mock，不真正发信；验证码发信后从 Redis 读回完成注册。
 * system_settings 的写入随 @Transactional 回滚，不影响真实配置。
 *
 * @author hanbing
 * @since 2026-09-26
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SystemSettingService systemSettingService;

    @MockitoBean
    private QqMailSender mailSender;

    private static String randomEmail() {
        return "t_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** 显式关闭邮箱验证码注册（不依赖库里的实时配置，事务结束回滚）。 */
    private void disableMailRegister() {
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "false");
    }

    /** 开启邮箱验证码注册并配好 SMTP（事务内写入，用例结束回滚）。 */
    private void enableMailRegister() {
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true");
        systemSettingService.set(SystemSetting.KEY_MAIL_USERNAME, "sender@qq.com");
        systemSettingService.set(SystemSetting.KEY_MAIL_AUTH_CODE, "dummy-auth-code");
    }

    @Test
    @DisplayName("注册参数校验失败返回 400")
    void registerValidation() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"validname\",\"password\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 开启邮箱验证码注册后：缺邮箱 / 邮箱格式非法 / 验证码格式非法都是 400
        enableMailRegister();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"validname\",\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("邮箱不能为空"));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"validname\",\"password\":\"secret123\","
                                + "\"email\":\"not-an-email\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("邮箱格式不正确"));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"validname\",\"password\":\"secret123\","
                                + "\"email\":\"a@example.com\",\"code\":\"12ab\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("验证码为 6 位数字"));
    }

    @Test
    @DisplayName("发码接口：未开启 403；邮箱非法 400；间隔内重发 429；成功返回重发间隔")
    void sendCode() throws Exception {
        // 关闭状态下发码 403
        disableMailRegister();
        mockMvc.perform(post("/api/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + randomEmail() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        enableMailRegister();
        mockMvc.perform(post("/api/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        // 非法 scene 值
        mockMvc.perform(post("/api/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + randomEmail() + "\",\"scene\":\"hack\"}"))
                .andExpect(status().isBadRequest());
        // 重置场景的码只发给已注册邮箱
        mockMvc.perform(post("/api/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + randomEmail() + "\",\"scene\":\"reset\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该邮箱未注册"));

        String email = randomEmail();
        mockMvc.perform(post("/api/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60));
        mockMvc.perform(post("/api/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    @DisplayName("注册白名单按完整域名匹配，拒绝时不发邮件与验证码")
    void registrationDomainWhitelist() throws Exception {
        enableMailRegister();
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_DOMAINS, "[\"qq.com\",\"edu.cn\"]");
        for (String domain : java.util.List.of("sub.qq.com", "evilqq.com", "qq.com.evil", "school.edu.cn")) {
            String email = "t_" + UUID.randomUUID().toString().substring(0, 8) + "@" + domain;
            mockMvc.perform(post("/api/auth/send-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("该邮箱域名不在注册白名单中"));
            org.junit.jupiter.api.Assertions.assertNull(redis.opsForValue().get("mail:code:register:" + email));
            org.junit.jupiter.api.Assertions.assertNull(redis.opsForValue().get("mail:code:lock:" + email));
        }
        org.mockito.Mockito.verifyNoInteractions(mailSender);
        String email = "t_" + UUID.randomUUID().toString().substring(0, 8) + "@QQ.COM";
        mockMvc.perform(post("/api/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertNotNull(redis.opsForValue().get(
                "mail:code:register:" + email.toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    @DisplayName("public-config 公开接口回传开关状态与社群链接")
    void publicConfig() throws Exception {
        disableMailRegister();
        systemSettingService.set(SystemSetting.KEY_COMMUNITY_LINK, "");
        mockMvc.perform(get("/api/auth/public-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mailVerify").value(false))
                .andExpect(jsonPath("$.data.communityLink").value(""))
                .andExpect(jsonPath("$.data.version").exists());

        enableMailRegister();
        systemSettingService.set(SystemSetting.KEY_COMMUNITY_LINK, "https://qm.qq.com/q/abc123");
        mockMvc.perform(get("/api/auth/public-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mailVerify").value(true))
                .andExpect(jsonPath("$.data.communityLink").value("https://qm.qq.com/q/abc123"));
    }

    @Test
    @DisplayName("重名注册 409；密码错误与用户不存在返回同一 401")
    void registerLoginFailures() throws Exception {
        enableMailRegister();
        String username = "t_" + UUID.randomUUID().toString().substring(0, 8);
        String email = randomEmail();

        // 发码 → 从 Redis 读回 → 注册
        mockMvc.perform(post("/api/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        String code = redis.opsForValue().get("mail:code:register:" + email);
        String body = "{\"username\":\"" + username + "\",\"password\":\"secret123\","
                + "\"email\":\"" + email + "\",\"code\":\"" + code + "\"}";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        String wrongPassword = "{\"username\":\"" + username + "\",\"password\":\"wrong-pass\"}";
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(wrongPassword))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no_such_user\",\"password\":\"secret123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }
}
