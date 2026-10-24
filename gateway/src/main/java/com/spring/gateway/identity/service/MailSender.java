package com.spring.gateway.identity.service;

/**
 * 邮件发送端口。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface MailSender {
    void send(String from, String authCode, String to, String subject, String plainText, String html);
}
