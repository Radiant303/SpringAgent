package com.spring.gateway.identity.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.spring.gateway.billing.service.PointsAccountService;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.identity.dto.LoginRequest;
import com.spring.gateway.identity.dto.RegisterRequest;
import com.spring.gateway.identity.dto.ResetPasswordRequest;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.identity.service.AuthService;
import com.spring.gateway.identity.service.AuthSessionService;
import com.spring.gateway.identity.service.MailCodeService;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.settings.service.SystemSettingService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

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
    private final AuthSessionService tokens = mock(AuthSessionService.class);
    private final BCryptPasswordEncoder passwords = mock(BCryptPasswordEncoder.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final MailCodeService codes = mock(MailCodeService.class);
    private final PointsAccountService points = mock(PointsAccountService.class);
    private final AuthService auth = new AuthServiceImpl(users, ids, tokens, passwords, settings, codes, points);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "domain-tests"), User.class);
        when(settings.getSnapshot()).thenReturn(Map.of(
                SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true",
                SystemSetting.KEY_REGISTRATION_OPEN, "true",
                SystemSetting.KEY_REGISTRATION_INITIAL_POINTS, "0",
                SystemSetting.KEY_MAIL_REGISTER_DOMAINS, "[\"qq.com\",\"edu.cn\"]"));
        when(settings.getMailRegisterDomains("[\"qq.com\",\"edu.cn\"]"))
                .thenReturn(List.of("qq.com", "edu.cn"));
        when(settings.getRegistrationInitialPoints(anyMap())).thenReturn(java.math.BigDecimal.ZERO);
        when(users.selectCount(null)).thenReturn(1L);
        when(ids.nextId()).thenReturn(42L);
        when(passwords.encode(anyString())).thenReturn("hash");
        when(tokens.createSession(anyLong())).thenReturn(new AuthSessionService.TokenBundle(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", 3600, 2592000, 42L));
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

    @Test
    void regularRegistrationUsesConfiguredInitialPoints() {
        when(settings.getRegistrationInitialPoints(anyMap())).thenReturn(new java.math.BigDecimal("25.125"));
        auth.register(new RegisterRequest("member", "password123", "person@qq.com", "123456"));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).insert(saved.capture());
        assertEquals(java.math.BigDecimal.ZERO, saved.getValue().getPointsBalance());
        assertEquals(User.ROLE_USER, saved.getValue().getRole());
        verify(points).grantRegistration(42L, new java.math.BigDecimal("25.125"));
    }

    @Test
    void bootstrapOwnerKeepsFixedInitialPoints() {
        when(users.selectCount(null)).thenReturn(0L);
        auth.register(new RegisterRequest("owner", "password123", "owner@qq.com", "123456"));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).insert(saved.capture());
        assertEquals(java.math.BigDecimal.ZERO, saved.getValue().getPointsBalance());
        assertEquals(User.ROLE_OWNER, saved.getValue().getRole());
        verify(settings, never()).getRegistrationInitialPoints(anyMap());
        verify(points).grantRegistration(42L, new java.math.BigDecimal("1000"));
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
        when(codes.sendCode(eq(normalized), eq(MailCodeService.Scene.REGISTER), anyMap())).thenReturn(60L);
        assertEquals(60L, auth.sendSceneCode(email, MailCodeService.Scene.REGISTER));
        verify(codes).sendCode(eq(normalized), eq(MailCodeService.Scene.REGISTER), anyMap());
    }

    @Test
    void emptyListAndMockitoDefaultListRemainUnrestricted() {
        when(settings.getMailRegisterDomains(nullable(String.class))).thenReturn(List.of());
        auth.register(new RegisterRequest("member", "password123", "person@outside.example", "123456"));
        auth.sendSceneCode("person@outside.example", MailCodeService.Scene.REGISTER);
        verify(codes).verifyCode("person@outside.example", "123456", MailCodeService.Scene.REGISTER);
        verify(codes).sendCode(eq("person@outside.example"), eq(MailCodeService.Scene.REGISTER), anyMap());
        SystemSettingService defaults = mock(SystemSettingService.class);
        when(defaults.getSnapshot()).thenReturn(Map.of(
                SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true",
                SystemSetting.KEY_REGISTRATION_OPEN, "true"));
        when(defaults.getRegistrationInitialPoints(anyMap())).thenReturn(java.math.BigDecimal.ZERO);
        AuthService defaultAuth = new AuthServiceImpl(users, ids, tokens, passwords, defaults, codes, points);
        assertEquals(List.of(), defaults.getMailRegisterDomains(null));
        defaultAuth.register(new RegisterRequest("second", "password123", "second@outside.example", "123456"));
    }

    @Test
    void disabledEmailVerificationKeepsUsernameRegistrationAndDoesNotStoreSuppliedEmail() {
        when(settings.getSnapshot()).thenReturn(Map.of(SystemSetting.KEY_REGISTRATION_OPEN, "true"));
        clearInvocations(settings);
        auth.register(new RegisterRequest("member", "password123", "person@outside.example", null));
        auth.register(new RegisterRequest("second", "password123", null, null));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users, times(2)).insert(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(user -> user.getEmail() == null));
        verify(settings, never()).getMailRegisterDomains(nullable(String.class));
        verifyNoInteractions(codes);
    }

    @Test
    void disabledEmailFeaturePreservesSendCodeRejectionWithoutDomainRead() {
        when(settings.getSnapshot()).thenReturn(Map.of());
        when(codes.sendCode(eq("person@outside.example"), eq(MailCodeService.Scene.REGISTER), anyMap()))
                .thenThrow(new BizException(403, "当前未开启邮箱验证码功能"));
        clearInvocations(settings);
        var failure = assertThrows(BizException.class,
                () -> auth.sendSceneCode("person@outside.example", MailCodeService.Scene.REGISTER));
        assertEquals(403, failure.getStatus());
        verify(settings, never()).getMailRegisterDomains(nullable(String.class));
    }

    @Test
    void invalidEmailIsRejectedBeforeCheckingDomains() {
        clearInvocations(settings);
        var failure = assertThrows(BizException.class,
                () -> auth.register(new RegisterRequest("member", "password123", "not-an-email", "123456")));
        assertEquals("邮箱格式不正确", failure.getMessage());
        assertThrows(BizException.class, () -> auth.sendSceneCode("not-an-email", MailCodeService.Scene.REGISTER));
        verify(settings, never()).getMailRegisterDomains(nullable(String.class));
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
        when(codes.sendCode(eq(existing.getEmail()), eq(MailCodeService.Scene.RESET), anyMap())).thenReturn(60L);
        clearInvocations(settings);
        assertEquals(60L, auth.sendSceneCode(existing.getEmail(), MailCodeService.Scene.RESET));
        auth.resetPassword(new ResetPasswordRequest(existing.getEmail(), "123456", "new-password"));
        assertEquals("existing", auth.login(new LoginRequest(existing.getEmail(), "password123")).username());
        auth.sendBindCode(1L, "new@outside.example");
        auth.bindEmail(1L, "new@outside.example", "123456");
        auth.sendMyCode(1L);
        verify(codes).sendCode(eq(existing.getEmail()), eq(MailCodeService.Scene.RESET), anyMap());
        verify(codes).sendCode(existing.getEmail(), MailCodeService.Scene.RESET);
        verify(codes).verifyCode(existing.getEmail(), "123456", MailCodeService.Scene.RESET);
        verify(codes).sendCode("new@outside.example", MailCodeService.Scene.BIND);
        verify(codes).verifyCode("new@outside.example", "123456", MailCodeService.Scene.BIND);
        verify(settings).getSnapshot();
        verifyNoMoreInteractions(settings);
    }
}
