package com.spring.gateway.service;

import com.spring.gateway.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 邮箱验证码凭证读取单元测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class MailCodeServiceUnitTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final QqMailSender sender = mock(QqMailSender.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final MailCodeService service = new MailCodeService(redis, sender, settings);

    @Test
    @SuppressWarnings("unchecked")
    void sendingUsesOneCredentialGroupRead() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(settings.isMailRegisterEnabled()).thenReturn(true);
        when(settings.getMailCredentials())
                .thenReturn(new SystemSettingService.MailCredentials("sender@example.test", "fakeSecret"));
        when(settings.getMailResendIntervalSeconds()).thenReturn(60L);
        when(settings.getMailCodeTtlSeconds()).thenReturn(300L);
        assertEquals(60L, service.sendCode(" User@example.test ", MailCodeService.Scene.REGISTER));
        verify(settings).getMailCredentials();
        verify(sender).send(eq("sender@example.test"), eq("fakeSecret"), eq("user@example.test"),
                anyString(), anyString(), anyString());
    }

    @Test
    void disabledMailDoesNotReadCredentials() {
        assertEquals(403, assertThrows(BizException.class,
                () -> service.sendCode("user@example.test", MailCodeService.Scene.REGISTER)).getStatus());
        verify(settings, never()).getMailCredentials();
        verifyNoInteractions(redis, sender);
    }

    @Test
    void incompleteCredentialsDoNotSendOrTouchRedis() {
        when(settings.isMailRegisterEnabled()).thenReturn(true);
        when(settings.getMailCredentials()).thenReturn(new SystemSettingService.MailCredentials("sender@example.test", ""));
        assertEquals(500, assertThrows(BizException.class,
                () -> service.sendCode("user@example.test", MailCodeService.Scene.REGISTER)).getStatus());
        verify(settings).getMailCredentials();
        verifyNoInteractions(redis, sender);
    }
}
