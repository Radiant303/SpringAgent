package com.spring.gateway.identity.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.RequiresOwner;
import com.spring.gateway.common.Result;
import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.identity.dto.AdminPasswordRequest;
import com.spring.gateway.billing.dto.AdminPointsAdjustRequest;
import com.spring.gateway.identity.dto.AdminQuotaRequest;
import com.spring.gateway.identity.dto.AdminRoleRequest;
import com.spring.gateway.identity.dto.AdminStatusRequest;
import com.spring.gateway.identity.dto.AdminUserOption;
import com.spring.gateway.identity.dto.AdminUserView;
import com.spring.gateway.identity.dto.AdminWorkQuotaRequest;
import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import com.spring.gateway.identity.service.AdminUserService;
import com.spring.gateway.billing.service.PointsAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理接口。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final PointsAccountService billingService;

    /**
     * 用户分页列表
     *
     * @param page 页码（1 起）
     * @param size 每页条数（上限 100）
     * @return 分页用户列表（按注册时间升序）
     */
    @GetMapping
    public Result<PageResult<AdminUserView>> list(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size) {
        return Result.ok(adminUserService.page(new PageQuery(page, size)).map(AdminUserController::toView));
    }

    /**
     * 筛选下拉选项：全部用户的 ID + 用户名
     *
     * @return 选项列表
     */
    @GetMapping("/options")
    public Result<List<AdminUserOption>> options() {
        return Result.ok(adminUserService.options());
    }

    /**
     * 禁用/启用账号；站长不可被禁用，管理员只能操作用户账号
     *
     * @param actorRole 当前用户角色
     * @param id        目标用户 ID
     * @param req       目标状态
     * @return 空数据返回体
     */
    @PostMapping("/{id}/status")
    public Result<Void> setStatus(@RequestAttribute(AuthInterceptor.ATTR_USER_ROLE) String actorRole,
                                  @PathVariable Long id,
                                  @Valid @RequestBody AdminStatusRequest req) {
        adminUserService.setStatus(actorRole, id, req.status());
        return Result.ok(null);
    }

    /**
     * 任命/罢免管理员
     *
     * @param id  目标用户 ID
     * @param req 目标角色（admin/user）
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可任命管理员")
    @PostMapping("/{id}/role")
    public Result<Void> setRole(@PathVariable Long id,
                                @Valid @RequestBody AdminRoleRequest req) {
        adminUserService.setRole(id, req.role());
        return Result.ok(null);
    }

    /**
     * 重置用户密码
     *
     * @param actorRole 当前用户角色
     * @param id        目标用户 ID
     * @param req       新密码
     * @return 空数据返回体
     */
    @PostMapping("/{id}/password")
    public Result<Void> resetPassword(@RequestAttribute(AuthInterceptor.ATTR_USER_ROLE) String actorRole,
                                      @PathVariable Long id,
                                      @Valid @RequestBody AdminPasswordRequest req) {
        adminUserService.resetPassword(actorRole, id, req.password());
        return Result.ok(null);
    }

    /**
     * 调整存储配额
     *
     * @param actorRole 当前用户角色
     * @param id        目标用户 ID
     * @param req       新配额（字节）
     * @return 空数据返回体
     */
    @PostMapping("/{id}/quota")
    public Result<Void> setQuota(@RequestAttribute(AuthInterceptor.ATTR_USER_ROLE) String actorRole,
                                 @PathVariable Long id,
                                 @Valid @RequestBody AdminQuotaRequest req) {
        adminUserService.setQuota(actorRole, id, req.quotaBytes());
        return Result.ok(null);
    }

    /**
     * 调整单工作区上限覆盖值；quotaBytes 为 null 时恢复跟随全局设置
     *
     * @param actorRole 当前用户角色
     * @param id        目标用户 ID
     * @param req       覆盖值或 null
     * @return 空数据返回体
     */
    @PostMapping("/{id}/work-quota")
    public Result<Void> setWorkQuota(@RequestAttribute(AuthInterceptor.ATTR_USER_ROLE) String actorRole,
                                     @PathVariable Long id,
                                     @Valid @RequestBody AdminWorkQuotaRequest req) {
        adminUserService.setWorkQuota(actorRole, id, req.quotaBytes());
        return Result.ok(null);
    }

    /**
     * 调账（充值/扣减积分）：写 ADJUST 流水，仅站长；不提供直接 set 余额的入口
     *
     * @param actorId 当前用户 ID（记账为操作人）
     * @param id      目标用户 ID
     * @param req     变动额与事由
     * @return 空数据返回体
     */
    @RequiresOwner(message = "仅站长可调账")
    @PostMapping("/{id}/points")
    public Result<Void> adjustPoints(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long actorId,
                                     @PathVariable Long id,
                                     @Valid @RequestBody AdminPointsAdjustRequest req) {
        billingService.adjust(actorId, id, req.delta(), req.reason());
        return Result.ok(null);
    }

    private static AdminUserView toView(com.spring.gateway.identity.entity.User user) {
        return new AdminUserView(
                String.valueOf(user.getId()),  // 字符串下发：雪花 ID 超 2^53，JS 数字会丢精度
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getQuotaBytes(),
                user.getWorkQuotaBytes(),
                user.getPointsBalance(),
                TimeFormat.isoUtc(user.getCreatedAt()));
    }
}
