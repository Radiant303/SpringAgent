package com.spring.gateway.identity.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.identity.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户资料查询实现。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {
    private final UserMapper userMapper;

    @Override
    public User require(long userId) {
        User user = find(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return user;
    }

    @Override
    public User find(long userId) {
        return userMapper.selectById(userId);
    }

    @Override
    public String boundEmail(long userId) {
        String email = require(userId).getEmail();
        return email == null ? "" : email;
    }
}
