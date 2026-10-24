package com.spring.gateway.settings.controller;

import com.spring.gateway.common.ResourceLimits;
import com.spring.gateway.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端数值限制查询。
 *
 * @author hanbing
 * @since 2026-10-13
 */
@RestController
@RequestMapping("/api/admin/resource-limits")
public class AdminResourceLimitsController {
    @GetMapping
    public Result<Map<String, Object>> get() {
        return Result.ok(ResourceLimits.view());
    }
}
