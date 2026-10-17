package com.spring.gateway.service;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.dto.ChangePasswordRequest;
import com.spring.gateway.dto.LoginRequest;
import com.spring.gateway.dto.RegisterRequest;
import com.spring.gateway.dto.ResetPasswordRequest;
import com.spring.gateway.dto.TokenResponse;
import com.spring.gateway.dto.UserResponse;
import com.spring.gateway.entity.SystemSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证业务集成测试。连接真实的 MySQL 与 Redis；
 * 通过 @Transactional 在用例结束后回滚（含 system_settings 写入），避免污染数据。
 * QqMailSender 被替换为 mock，不真正发信；验证码发信后从 Redis 读回完成注册。
 *
 * @author hanbing
 * @since 2026-09-26
 */
@SpringBootTest
@Transactional
class AuthServiceTests {

    @Autowired
    private AuthService authService;
    @Autowired
    private MailCodeService mailCodeService;
    @Autowired
    private SystemSettingService systemSettingService;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private StringRedisTemplate redis;

    @MockitoBean
    private QqMailSender mailSender;

    /**
     * 生成随机用户名，避免用例之间因唯一索引冲突相互影响
     *
     * @return 随机用户名
     */
    private static String randomUsername() {
        return "t_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 生成随机邮箱（不会真实送达，发信已被 mock）。 */
    private static String randomEmail() {
        return "t_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** 开启邮箱验证码注册并配好 SMTP（事务内写入，用例结束回滚）。 */
    private void enableMailRegister() {
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true");
        systemSettingService.set(SystemSetting.KEY_MAIL_USERNAME, "sender@qq.com");
        systemSettingService.set(SystemSetting.KEY_MAIL_AUTH_CODE, "dummy-auth-code");
    }

    /**
     * 走一遍真实的发码流程，从 Redis 读回验证码
     *
     * @param email 收件邮箱
     * @return Redis 中存着的 6 位验证码
     */
    private String issueCode(String email) {
        mailCodeService.sendCode(email, MailCodeService.Scene.REGISTER);
        String code = redis.opsForValue().get("mail:code:register:" + email);
        assertNotNull(code, "发码后 Redis 里应能读到验证码");
        return code;
    }

    /** 发重置密码验证码并读回（重置与注册共享邮箱限流锁，测试里先清锁模拟间隔已过）。 */
    private String issueResetCode(String email) {
        redis.delete("mail:code:lock:" + email);
        authService.sendSceneCode(email, MailCodeService.Scene.RESET);
        String code = redis.opsForValue().get("mail:code:reset:" + email);
        assertNotNull(code, "发码后 Redis 里应能读到重置验证码");
        return code;
    }

    /** 用随机用户名 + 随机邮箱完成注册（需先 enableMailRegister）。 */
    private UserResponse register(String username, String email) {
        return authService.register(new RegisterRequest(username, "pass123456", email, issueCode(email)));
    }

    @Test
    @DisplayName("注册→登录全链路：id 是雪花的量级，token 能解回同一个用户")
    void registerThenLogin() {
        enableMailRegister();
        register(randomUsername(), randomEmail());
        String username = randomUsername();
        UserResponse registered = register(username, randomEmail());

        assertNotNull(registered.userId());
        assertTrue(Long.parseLong(registered.userId()) > 1_000_000_000_000_000L,
                "雪花 ID 应是 18 位量级，实际: " + registered.userId());

        TokenResponse login = authService.login(new LoginRequest(username, "pass123456"));
        assertEquals(Long.parseLong(registered.userId()), jwtUtils.parseUserId(login.token()));
        assertEquals(username, login.username());
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(login.pointsBalance()));
    }

    @Test
    @DisplayName("重复注册返回 409（用户名或邮箱任一撞车都拒绝）")
    void duplicateRegisterConflict() {
        enableMailRegister();
        String username = randomUsername();
        String email = randomEmail();
        register(username, email);

        // 用户名撞车：冲突检查在验证码校验之前，随便给个码即可
        BizException dupName = assertThrows(BizException.class,
                () -> authService.register(new RegisterRequest(username, "pass123456", randomEmail(), "000000")));
        assertEquals(409, dupName.getStatus());

        // 邮箱撞车
        BizException dupEmail = assertThrows(BizException.class,
                () -> authService.register(new RegisterRequest(randomUsername(), "pass123456", email, "000000")));
        assertEquals(409, dupEmail.getStatus());
        assertEquals("该邮箱已被注册", dupEmail.getMessage());
    }

    @Test
    @DisplayName("验证码错误或已过期返回 400；验证码一次性，用过即销毁")
    void codeVerifiedOnce() {
        enableMailRegister();
        String email = randomEmail();
        issueCode(email);

        BizException wrong = assertThrows(BizException.class,
                () -> authService.register(new RegisterRequest(randomUsername(), "pass123456", email, "000000")));
        assertEquals(400, wrong.getStatus());

        // 用正确验证码注册成功后，验证码立即销毁（一次性），不能再用
        String code = redis.opsForValue().get("mail:code:register:" + email);
        authService.register(new RegisterRequest(randomUsername(), "pass123456", email, code));
        assertNull(redis.opsForValue().get("mail:code:register:" + email), "验证通过后验证码应立即销毁");
    }

    @Test
    @DisplayName("同一邮箱重发间隔内再次发码返回 429")
    void resendWithinIntervalRejected() {
        enableMailRegister();
        String email = randomEmail();
        mailCodeService.sendCode(email, MailCodeService.Scene.REGISTER);
        BizException e = assertThrows(BizException.class,
                () -> mailCodeService.sendCode(email, MailCodeService.Scene.REGISTER));
        assertEquals(429, e.getStatus());
    }

    @Test
    @DisplayName("关闭邮箱验证码注册：注册无需邮箱与验证码，发码接口 403")
    void mailRegisterDisabled() {
        // 显式关闭（不依赖库里的实时状态），事务结束回滚
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "false");

        String username = randomUsername();
        UserResponse registered = authService.register(new RegisterRequest(username, "pass123456", null, null));
        assertNotNull(registered.userId());

        BizException e = assertThrows(BizException.class,
                () -> mailCodeService.sendCode(randomEmail(), MailCodeService.Scene.REGISTER));
        assertEquals(403, e.getStatus());
    }

    @Test
    @DisplayName("开启后缺邮箱 / 缺验证码 / 格式非法都返回 400")
    void mailRegisterEnabledRequiresEmailAndCode() {
        enableMailRegister();
        String username = randomUsername();

        BizException noEmail = assertThrows(BizException.class,
                () -> authService.register(new RegisterRequest(username, "pass123456", null, null)));
        assertEquals(400, noEmail.getStatus());
        assertEquals("邮箱不能为空", noEmail.getMessage());

        BizException badEmail = assertThrows(BizException.class,
                () -> authService.register(new RegisterRequest(username, "pass123456", "not-an-email", "123456")));
        assertEquals(400, badEmail.getStatus());

        BizException badCode = assertThrows(BizException.class,
                () -> authService.register(new RegisterRequest(username, "pass123456", randomEmail(), "12ab")));
        assertEquals(400, badCode.getStatus());
        assertEquals("验证码为 6 位数字", badCode.getMessage());
    }

    @Test
    @DisplayName("开启了但未配置发件邮箱与授权码时，发码返回 500")
    void sendCodeWithoutMailConfig() {
        // 显式构造"开了开关但没配 SMTP"的状态（不依赖库里的实时配置），事务结束回滚
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true");
        systemSettingService.set(SystemSetting.KEY_MAIL_USERNAME, "");
        systemSettingService.set(SystemSetting.KEY_MAIL_AUTH_CODE, "");
        BizException e = assertThrows(BizException.class,
                () -> mailCodeService.sendCode(randomEmail(), MailCodeService.Scene.RESET));
        assertEquals(500, e.getStatus());
        assertEquals("邮箱验证码服务未配置，请联系站长", e.getMessage());
    }

    @Test
    @DisplayName("密码错误与用户不存在都返回同一 401")
    void loginFailureUnified401() {
        enableMailRegister();
        String username = randomUsername();
        register(username, randomEmail());

        BizException wrongPwd = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest(username, "wrong-password")));
        BizException noUser = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest(randomUsername(), "pass123456")));

        assertEquals(401, wrongPwd.getStatus());
        assertEquals(401, noUser.getStatus());
        assertEquals(wrongPwd.getMessage(), noUser.getMessage());
    }

    @Test
    @DisplayName("支持用注册邮箱登录；邮箱登错密码同样返回统一 401")
    void loginByEmail() {
        enableMailRegister();
        String username = randomUsername();
        String email = randomEmail();
        register(username, email);

        TokenResponse login = authService.login(new LoginRequest(email, "pass123456"));
        assertEquals(username, login.username());

        BizException wrongPwd = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest(email, "wrong-password")));
        assertEquals(401, wrongPwd.getStatus());
        assertEquals("用户名或密码错误", wrongPwd.getMessage());
    }

    @Test
    @DisplayName("重置密码全链路：发码→重置→旧密码失效新密码可登录（支持邮箱登录）")
    void resetPasswordFlow() {
        enableMailRegister();
        String username = randomUsername();
        String email = randomEmail();
        register(username, email);

        String code = issueResetCode(email);
        authService.resetPassword(new ResetPasswordRequest(email, code, "newpass789"));

        BizException oldPwd = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest(username, "pass123456")));
        assertEquals(401, oldPwd.getStatus());
        TokenResponse login = authService.login(new LoginRequest(email, "newpass789"));
        assertEquals(username, login.username());

        // 验证码一次性：同一个码不能重置第二次
        BizException reused = assertThrows(BizException.class,
                () -> authService.resetPassword(new ResetPasswordRequest(email, code, "another123")));
        assertEquals(400, reused.getStatus());
    }

    @Test
    @DisplayName("重置场景要求邮箱已注册；注册场景拒绝已注册邮箱（发码即快速失败）")
    void sceneOwnershipChecks() {
        enableMailRegister();
        String registeredEmail = randomEmail();
        register(randomUsername(), registeredEmail);

        BizException registerScene = assertThrows(BizException.class,
                () -> authService.sendSceneCode(registeredEmail, MailCodeService.Scene.REGISTER));
        assertEquals(409, registerScene.getStatus());

        BizException resetScene = assertThrows(BizException.class,
                () -> authService.sendSceneCode(randomEmail(), MailCodeService.Scene.RESET));
        assertEquals(400, resetScene.getStatus());
        assertEquals("该邮箱未注册", resetScene.getMessage());

        BizException resetUnknown = assertThrows(BizException.class,
                () -> authService.resetPassword(new ResetPasswordRequest(randomEmail(), "123456", "newpass789")));
        assertEquals(400, resetUnknown.getStatus());
    }

    @Test
    @DisplayName("场景隔离：注册码不能用于重置密码")
    void sceneIsolation() {
        enableMailRegister();
        String email = randomEmail();
        register(randomUsername(), email);
        // 注册场景发的码存在 register 命名空间，按 reset 命名空间校验必然不匹配
        // （注册时发码已占限流锁，清锁模拟 60s 间隔已过——这里要测的是场景隔离而不是限流）
        redis.delete("mail:code:lock:" + email);
        String registerCode = issueCode(email);
        BizException e = assertThrows(BizException.class,
                () -> authService.resetPassword(new ResetPasswordRequest(email, registerCode, "newpass789")));
        assertEquals(400, e.getStatus());
        assertEquals("验证码错误或已过期", e.getMessage());
    }

    @Test
    @DisplayName("开启邮箱功能：改密凭绑定邮箱验证码（错码 400），改后旧密码失效")
    void changePasswordFlow() {
        enableMailRegister();
        String username = randomUsername();
        String email = randomEmail();
        Long userId = Long.parseLong(register(username, email).userId());

        // 错码 → 400
        BizException wrong = assertThrows(BizException.class,
                () -> authService.changePassword(userId, new ChangePasswordRequest(null, "000000", "changed456")));
        assertEquals(400, wrong.getStatus());

        // 发码（注册发码已占限流锁，清锁模拟间隔已过）→ 正确验证码改密
        redis.delete("mail:code:lock:" + email);
        authService.sendMyCode(userId);
        String code = redis.opsForValue().get("mail:code:reset:" + email);
        assertNotNull(code);
        authService.changePassword(userId, new ChangePasswordRequest(null, code, "changed456"));

        BizException oldPwd = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest(username, "pass123456")));
        assertEquals(401, oldPwd.getStatus());
        assertEquals(username, authService.login(new LoginRequest(username, "changed456")).username());
    }

    @Test
    @DisplayName("开启邮箱功能时改密以绑定邮箱为前提（服务端强制）；关闭时退回旧密码校验")
    void changePasswordRequiresBoundEmailWhenMailOn() {
        // 关闭状态注册：无邮箱；关闭时旧密码正确即可改
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "false");
        Long userId = Long.parseLong(
                authService.register(new RegisterRequest(randomUsername(), "pass123456", null, null)).userId());
        authService.changePassword(userId, new ChangePasswordRequest("pass123456", null, "changed456"));

        // 关闭时旧密码错误 → 400
        BizException wrongOld = assertThrows(BizException.class,
                () -> authService.changePassword(userId, new ChangePasswordRequest("wrong-old", null, "changed789")));
        assertEquals(400, wrongOld.getStatus());
        assertEquals("旧密码错误", wrongOld.getMessage());

        // 开启后：未绑定邮箱 → 400 前提拦截
        enableMailRegister();
        BizException e = assertThrows(BizException.class,
                () -> authService.changePassword(userId, new ChangePasswordRequest("changed456", null, "changed789")));
        assertEquals(400, e.getStatus());
        assertEquals("请先绑定邮箱后再修改密码", e.getMessage());

        // 绑定邮箱后即可改（凭验证码；sendMyCode 发到绑定邮箱）
        String email = randomEmail();
        authService.sendBindCode(userId, email);
        String bindCode = redis.opsForValue().get("mail:code:bind:" + email);
        assertNotNull(bindCode);
        authService.bindEmail(userId, email, bindCode);

        // 绑定发码已占限流锁，清锁模拟间隔已过
        redis.delete("mail:code:lock:" + email);
        authService.sendMyCode(userId);
        String resetCode = redis.opsForValue().get("mail:code:reset:" + email);
        assertNotNull(resetCode);
        authService.changePassword(userId, new ChangePasswordRequest(null, resetCode, "changed789"));
    }

    @Test
    @DisplayName("绑定邮箱：发码→验证→绑定成功，之后可用邮箱登录")
    void bindEmailFlow() {
        systemSettingService.set(SystemSetting.KEY_MAIL_REGISTER_ENABLED, "false");
        String username = randomUsername();
        Long userId = Long.parseLong(
                authService.register(new RegisterRequest(username, "pass123456", null, null)).userId());

        enableMailRegister();
        String email = randomEmail();
        authService.sendBindCode(userId, email);
        String code = redis.opsForValue().get("mail:code:bind:" + email);
        assertNotNull(code);

        authService.bindEmail(userId, email, code);
        // 绑定后可用邮箱登录
        assertEquals(username, authService.login(new LoginRequest(email, "pass123456")).username());
    }

    @Test
    @DisplayName("绑定已被占用的邮箱 409；错码 400")
    void bindEmailConflicts() {
        enableMailRegister();
        String takenEmail = randomEmail();
        register(randomUsername(), takenEmail);  // 已有用户绑定该邮箱

        String myEmail = randomEmail();
        Long userId = Long.parseLong(
                authService.register(new RegisterRequest(randomUsername(), "pass123456", myEmail, issueCode(myEmail))).userId());
        BizException send = assertThrows(BizException.class,
                () -> authService.sendBindCode(userId, takenEmail));
        assertEquals(409, send.getStatus());

        String email = randomEmail();
        authService.sendBindCode(userId, email);
        BizException wrongCode = assertThrows(BizException.class,
                () -> authService.bindEmail(userId, email, "000000"));
        assertEquals(400, wrongCode.getStatus());
    }
}
