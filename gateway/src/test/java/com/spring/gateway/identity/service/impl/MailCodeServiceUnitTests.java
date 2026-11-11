package com.spring.gateway.identity.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.identity.service.MailCodeService;
import com.spring.gateway.identity.service.MailSender;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.settings.service.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 邮箱验证码设置读取单元测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class MailCodeServiceUnitTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MailSender sender = mock(MailSender.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final MailCodeService service = new MailCodeServiceImpl(redis, sender, settings);

    @Test
    @SuppressWarnings("unchecked")
    void sendingUsesOneSettingsSnapshot() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(settings.getSnapshot()).thenReturn(Map.of(
                SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true",
                SystemSetting.KEY_MAIL_USERNAME, "sender@example.test",
                SystemSetting.KEY_MAIL_AUTH_CODE, "fakeSecret",
                SystemSetting.KEY_MAIL_RESEND_INTERVAL_SECONDS, "60",
                SystemSetting.KEY_MAIL_CODE_TTL_SECONDS, "300"));
        assertEquals(60L, service.sendCode(" User@example.test ", MailCodeService.Scene.REGISTER));
        verify(settings).getSnapshot();
        verifyNoMoreInteractions(settings);
        verify(sender).send(eq("sender@example.test"), eq("fakeSecret"), eq("user@example.test"),
                anyString(), anyString(), anyString());
    }

    @Test
    void disabledMailDoesNotReadCredentials() {
        when(settings.getSnapshot()).thenReturn(Map.of());
        assertEquals(403, assertThrows(BizException.class,
                () -> service.sendCode("user@example.test", MailCodeService.Scene.REGISTER)).getStatus());
        verify(settings).getSnapshot();
        verify(settings, never()).getMailCredentials();
        verifyNoInteractions(redis, sender);
    }

    @Test
    void incompleteCredentialsDoNotSendOrTouchRedis() {
        when(settings.getSnapshot()).thenReturn(Map.of(
                SystemSetting.KEY_MAIL_REGISTER_ENABLED, "true",
                SystemSetting.KEY_MAIL_USERNAME, "sender@example.test"));
        assertEquals(500, assertThrows(BizException.class,
                () -> service.sendCode("user@example.test", MailCodeService.Scene.REGISTER)).getStatus());
        verify(settings).getSnapshot();
        verify(settings, never()).getMailCredentials();
        verifyNoInteractions(redis, sender);
    }
}
