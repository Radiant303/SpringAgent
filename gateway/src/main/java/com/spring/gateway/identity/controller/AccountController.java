package com.spring.gateway.identity.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.Result;
import com.spring.gateway.identity.dto.BindEmailRequest;
import com.spring.gateway.identity.dto.ChangePasswordRequest;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.identity.service.AuthService;
import com.spring.gateway.identity.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 当前登录用户的账户接口。
 *
 * @author hanbing
 * @since 2026-10-10
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {
    private final AuthService authService;
    private final UserProfileService userProfileService;

    /**
     * 当前用户资料。
     *
     * @param userId 当前用户 ID
     * @return 用户名与绑定邮箱
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        User user = userProfileService.require(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        return Result.ok(data);
    }

    /**
     * 给待绑定邮箱发验证码。
     *
     * @param userId 当前用户 ID
     * @param req 待绑定邮箱
     * @return 重发间隔秒数
     */
    @PostMapping("/send-bind-code")
    public Result<Map<String, Long>> sendBindCode(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                                  @Valid @RequestBody BindEmailRequest req) {
        return Result.ok(Map.of("resendAfterSeconds", authService.sendBindCode(userId, req.email())));
    }

    /**
     * 凭验证码绑定或换绑邮箱。
     *
     * @param userId 当前用户 ID
     * @param req 邮箱与验证码
     * @return 空数据返回体
     */
    @PostMapping("/bind-email")
    public Result<Void> bindEmail(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                  @Valid @RequestBody BindEmailRequest req) {
        authService.bindEmail(userId, req.email(), req.code());
        return Result.ok(null);
    }

    /**
     * 给当前账号的绑定邮箱发验证码。
     *
     * @param userId 当前用户 ID
     * @return 重发间隔秒数
     */
    @PostMapping("/send-code")
    public Result<Map<String, Long>> sendCode(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return Result.ok(Map.of("resendAfterSeconds", authService.sendMyCode(userId)));
    }

    /**
     * 修改密码。
     *
     * @param userId 当前用户 ID
     * @param req 旧密码或验证码与新密码
     * @return 空数据返回体
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                       @Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(userId, req);
        return Result.ok(null);
    }
}
