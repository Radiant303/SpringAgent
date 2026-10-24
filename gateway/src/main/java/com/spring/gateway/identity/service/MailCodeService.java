package com.spring.gateway.identity.service;

/**
 * 邮箱验证码业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface MailCodeService {
    long sendCode(String email, Scene scene);

    void verifyCode(String email, String code, Scene scene);

    static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    static boolean isValidEmail(String email) {
        return java.util.regex.Pattern.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", email);
    }

    enum Scene {
        REGISTER("register", "注册"),
        RESET("reset", "重置密码"),
        BIND("bind", "绑定邮箱"),
        MAIL_DISABLE("mail-disable", "关闭邮箱功能");

        private final String keySegment;
        private final String actionText;

        Scene(String keySegment, String actionText) {
            this.keySegment = keySegment;
            this.actionText = actionText;
        }

        public String keySegment() {
            return keySegment;
        }

        public String actionText() {
            return actionText;
        }
    }
}
