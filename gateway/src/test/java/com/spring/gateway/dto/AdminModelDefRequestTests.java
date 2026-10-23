package com.spring.gateway.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型定义请求校验测试。
 *
 * @author hanbing
 * @since 2026-10-23
 */
class AdminModelDefRequestTests {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static AdminModelDefRequest requestWithCapabilities(List<String> capabilities) {
        return new AdminModelDefRequest(null, "provider", "model", "展示名", 1000L, 0L,
                capabilities, List.of(), "", true);
    }

    @Test
    @DisplayName("模型能力允许原生搜索声明")
    void acceptsNativeSearchCapability() {
        var violations = VALIDATOR.validate(requestWithCapabilities(List.of("native_search")));
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("模型能力仍拒绝未知值")
    void rejectsUnknownCapability() {
        var violations = VALIDATOR.validate(requestWithCapabilities(List.of("unknown")));
        assertFalse(violations.isEmpty());
    }
}
