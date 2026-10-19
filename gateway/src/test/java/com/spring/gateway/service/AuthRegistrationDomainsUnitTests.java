package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.dto.LoginRequest;
import com.spring.gateway.dto.RegisterRequest;
import com.spring.gateway.dto.ResetPasswordRequest;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 注册邮箱域名限制单元测试。
 *
 * @author hanbing
 * @since 2026-10-19
 */
class AuthRegistrationDomainsUnitTests {
    private final UserMapper users = mock(UserMapper.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final JwtUtils tokens = mock(JwtUtils.class);
    private final BCryptPasswordEncoder passwords = mock(BCryptPasswordEncoder.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final MailCodeService codes = mock(MailCodeService.class);
    private final AuthService auth = new AuthService(users, ids, tokens, passwords, settings, codes);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "domain-tests"), User.class);
        when(settings.isMailRegisterEnabled()).thenReturn(true);
        when(settings.isRegistrationOpen()).thenReturn(true);
        when(settings.getMailRegisterDomains()).thenReturn(List.of("qq.com", "edu.cn"));
        when(users.selectCount(null)).thenReturn(1L);
        when(ids.nextId()).thenReturn(42L);
        when(passwords.encode(anyString())).thenReturn("hash");
    }

    @ParameterizedTest
    @ValueSource(strings = {"person@qq.com", " Person@QQ.COM ", "person@edu.cn", "person@EDU.CN"})
    void exactDomainsCanRegisterAndConsumeCodeBeforeInsert(String email) {
        assertEquals("42", auth.register(new RegisterRequest("member", "password123", email, "123456")).userId());
        String normalized = MailCodeService.normalize(email);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        var order = inOrder(codes, users);
        order.verify(codes).verifyCode(normalized, "123456", MailCodeService.Scene.REGISTER);
        order.verify(users).insert(saved.capture());
        assertEquals(normalized, saved.getValue().getEmail());
        assertEquals(User.ROLE_USER, saved.getValue().getRole());
    }

    @ParameterizedTest
    @ValueSource(strings = {"person@sub.qq.com", "person@evilqq.com", "person@qq.com.evil",
            "person@school.edu.cn", "person@eviledu.cn", "person@edu.cn.evil", "person@example.com"})
    void nonmatchingDomainsRejectRegistrationAndSendingWithoutConsumingOrSending(String email) {
        var registration = assertThrows(BizException.class,
                () -> auth.register(new RegisterRequest("member", "password123", email, "123456")));
        assertEquals(400, registration.getStatus());
        assertEquals("该邮箱域名不在注册白名单中", registration.getMessage());
        var sending = assertThrows(BizException.class, () -> auth.sendSceneCode(email, MailCodeService.Scene.REGISTER));
        assertEquals(400, sending.getStatus());
        verifyNoInteractions(codes, users, ids, passwords);
    }

    @ParameterizedTest
    @ValueSource(strings = {"person@qq.com", " Person@EDU.CN "})
    void allowedDomainsSendRegisterCode(String email) {
        String normalized = MailCodeService.normalize(email);
        when(codes.sendCode(normalized, MailCodeService.Scene.REGISTER)).thenReturn(60L);
        assertEquals(60L, auth.sendSceneCode(email, MailCodeService.Scene.REGISTER));
        verify(codes).sendCode(normalized, MailCodeService.Scene.REGISTER);
    }

    @Test
    void emptyListAndMockitoDefaultListRemainUnrestricted() {
        when(settings.getMailRegisterDomains()).thenReturn(List.of());
        auth.register(new RegisterRequest("member", "password123", "person@outside.example", "123456"));
        auth.sendSceneCode("person@outside.example", MailCodeService.Scene.REGISTER);
        verify(codes).verifyCode("person@outside.example", "123456", MailCodeService.Scene.REGISTER);
        verify(codes).sendCode("person@outside.example", MailCodeService.Scene.REGISTER);
        SystemSettingService defaults = mock(SystemSettingService.class);
        when(defaults.isMailRegisterEnabled()).thenReturn(true);
        when(defaults.isRegistrationOpen()).thenReturn(true);
        AuthService defaultAuth = new AuthService(users, ids, tokens, passwords, defaults, codes);
        assertEquals(List.of(), defaults.getMailRegisterDomains());
        defaultAuth.register(new RegisterRequest("second", "password123", "second@outside.example", "123456"));
    }

    @Test
    void disabledEmailVerificationKeepsUsernameRegistrationAndDoesNotStoreSuppliedEmail() {
        when(settings.isMailRegisterEnabled()).thenReturn(false);
        clearInvocations(settings);
        auth.register(new RegisterRequest("member", "password123", "person@outside.example", null));
        auth.register(new RegisterRequest("second", "password123", null, null));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users, times(2)).insert(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(user -> user.getEmail() == null));
        verify(settings, never()).getMailRegisterDomains();
        verifyNoInteractions(codes);
    }

    @Test
    void disabledEmailFeaturePreservesSendCodeRejectionWithoutDomainRead() {
        when(settings.isMailRegisterEnabled()).thenReturn(false);
        when(codes.sendCode("person@outside.example", MailCodeService.Scene.REGISTER))
                .thenThrow(new BizException(403, "当前未开启邮箱验证码功能"));
        clearInvocations(settings);
        var failure = assertThrows(BizException.class,
                () -> auth.sendSceneCode("person@outside.example", MailCodeService.Scene.REGISTER));
        assertEquals(403, failure.getStatus());
        verify(settings, never()).getMailRegisterDomains();
    }

    @Test
    void invalidEmailIsRejectedBeforeCheckingDomains() {
        clearInvocations(settings);
        var failure = assertThrows(BizException.class,
                () -> auth.register(new RegisterRequest("member", "password123", "not-an-email", "123456")));
        assertEquals("邮箱格式不正确", failure.getMessage());
        assertThrows(BizException.class, () -> auth.sendSceneCode("not-an-email", MailCodeService.Scene.REGISTER));
        verify(settings, never()).getMailRegisterDomains();
        verifyNoInteractions(codes);
    }

    @Test
    void resetLoginAndBindingIgnoreRegistrationDomains() {
        User existing = new User();
        existing.setId(1L);
        existing.setUsername("existing");
        existing.setEmail("existing@outside.example");
        existing.setPasswordHash("hash");
        existing.setStatus(User.STATUS_ACTIVE);
        existing.setRole(User.ROLE_USER);
        when(users.selectOne(any())).thenReturn(existing);
        when(users.selectById(1L)).thenReturn(existing);
        when(users.exists(any())).thenReturn(true, false, false);
        when(passwords.matches("password123", "hash")).thenReturn(true);
        when(codes.sendCode(existing.getEmail(), MailCodeService.Scene.RESET)).thenReturn(60L);
        clearInvocations(settings);
        assertEquals(60L, auth.sendSceneCode(existing.getEmail(), MailCodeService.Scene.RESET));
        auth.resetPassword(new ResetPasswordRequest(existing.getEmail(), "123456", "new-password"));
        assertEquals("existing", auth.login(new LoginRequest(existing.getEmail(), "password123")).username());
        auth.sendBindCode(1L, "new@outside.example");
        auth.bindEmail(1L, "new@outside.example", "123456");
        auth.sendMyCode(1L);
        verify(codes, times(2)).sendCode(existing.getEmail(), MailCodeService.Scene.RESET);
        verify(codes).verifyCode(existing.getEmail(), "123456", MailCodeService.Scene.RESET);
        verify(codes).sendCode("new@outside.example", MailCodeService.Scene.BIND);
        verify(codes).verifyCode("new@outside.example", "123456", MailCodeService.Scene.BIND);
        verifyNoInteractions(settings);
    }
}
