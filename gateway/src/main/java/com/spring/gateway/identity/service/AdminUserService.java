package com.spring.gateway.identity.service;

import com.spring.gateway.dto.PageQuery;
import com.spring.gateway.dto.PageResult;
import com.spring.gateway.identity.dto.AdminUserOption;
import com.spring.gateway.identity.entity.User;

import java.util.List;

/**
 * 用户管理业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface AdminUserService {
    PageResult<User> page(PageQuery q);

    List<AdminUserOption> options();

    void setStatus(String actorRole, Long targetId, String status);

    void setRole(Long targetId, String role);

    void resetPassword(String actorRole, Long targetId, String password);

    void setQuota(String actorRole, Long targetId, Long quotaBytes);

    void setWorkQuota(String actorRole, Long targetId, Long quotaBytes);
}
