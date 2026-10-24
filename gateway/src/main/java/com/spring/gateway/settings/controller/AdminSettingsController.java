package com.spring.gateway.settings.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.RequiresOwner;
import com.spring.gateway.common.Result;
import com.spring.gateway.settings.dto.AdminBillingEstRequest;
import com.spring.gateway.settings.dto.AdminCommunityLinkRequest;
import com.spring.gateway.settings.dto.AdminMailSettingsRequest;
import com.spring.gateway.settings.dto.AdminPlatformSettingsRequest;
import com.spring.gateway.settings.dto.AdminRegistrationRequest;
import com.spring.gateway.settings.dto.AdminSearchSettingsRequest;
import com.spring.gateway.settings.dto.AdminStorageSettingsRequest;
import com.spring.gateway.settings.dto.AdminWorkMaxBytesRequest;
import com.spring.gateway.settings.service.SystemSettingAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统设置接口。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {
    private final SystemSettingAdminService systemSettingAdminService;

    /**
     * 当前系统设置。
     *
     * @return 设置快照
     */
    @GetMapping
    public Result<Map<String, Object>> get() {
        return Result.ok(systemSettingAdminService.snapshot());
    }

    /**
     * 设置搜索计费与厂商。
     *
     * @param req 搜索设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/search")
    public Result<Void> setSearch(@Valid @RequestBody AdminSearchSettingsRequest req) {
        systemSettingAdminService.setSearch(req);
        return Result.ok(null);
    }

    /**
     * 设置注册开关与邮箱域名白名单。
     *
     * @param req 注册设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/registration")
    public Result<Void> setRegistration(@Valid @RequestBody AdminRegistrationRequest req) {
        systemSettingAdminService.setRegistration(req);
        return Result.ok(null);
    }

    /**
     * 设置全局单工作区上限。
     *
     * @param req 上限字节数
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/work-max-bytes")
    public Result<Void> setWorkMaxBytes(@Valid @RequestBody AdminWorkMaxBytesRequest req) {
        systemSettingAdminService.setWorkMaxBytes(req);
        return Result.ok(null);
    }

    /**
     * 给当前站长的绑定邮箱发送关闭功能验证码。
     *
     * @param userId 当前站长 ID
     * @return 重发间隔秒数
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/mail/send-disable-code")
    public Result<Map<String, Long>> sendMailDisableCode(
            @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return Result.ok(Map.of("resendAfterSeconds", systemSettingAdminService.sendMailDisableCode(userId)));
    }

    /**
     * 配置邮箱功能。
     *
     * @param userId 当前站长 ID
     * @param req 邮箱设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/mail")
    public Result<Void> setMail(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                @Valid @RequestBody AdminMailSettingsRequest req) {
        systemSettingAdminService.setMail(userId, req);
        return Result.ok(null);
    }

    /**
     * 设置官方社群链接。
     *
     * @param req 社群链接
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/community-link")
    public Result<Void> setCommunityLink(@Valid @RequestBody AdminCommunityLinkRequest req) {
        systemSettingAdminService.setCommunityLink(req);
        return Result.ok(null);
    }

    /**
     * 设置平台对外地址。
     *
     * @param req 平台设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/platform")
    public Result<Void> setPlatform(@Valid @RequestBody AdminPlatformSettingsRequest req) {
        systemSettingAdminService.setPlatform(req);
        return Result.ok(null);
    }

    /**
     * 配置文件存储。
     *
     * @param req 存储设置
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/storage")
    public Result<Void> setStorage(@Valid @RequestBody AdminStorageSettingsRequest req) {
        systemSettingAdminService.setStorage(req);
        return Result.ok(null);
    }

    /**
     * 设置预扣预估档位。
     *
     * @param req 三档 Token 数
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可修改系统设置")
    @PostMapping("/billing-est")
    public Result<Void> setBillingEst(@Valid @RequestBody AdminBillingEstRequest req) {
        systemSettingAdminService.setBillingEst(req);
        return Result.ok(null);
    }
}
