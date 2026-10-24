package com.spring.gateway.identity.service;

import com.spring.gateway.identity.entity.User;

/**
 * 用户资料查询业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface UserProfileService {
    User require(long userId);

    User find(long userId);

    String boundEmail(long userId);
}
