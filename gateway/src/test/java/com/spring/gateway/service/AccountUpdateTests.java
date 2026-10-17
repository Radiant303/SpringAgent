package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.dto.ChangePasswordRequest;
import com.spring.gateway.dto.ResetPasswordRequest;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 账户字段更新范围的回归测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class AccountUpdateTests {
    private UserMapper users;
    private AuthService auth;
    private AdminUserService admin;

    @BeforeEach
    void prepare() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "account-tests"), User.class);
        users = mock(UserMapper.class);
        User stale = new User();
        stale.setId(1L);
        stale.setEmail("old@example.com");
        stale.setRole(User.ROLE_USER);
        stale.setPasswordHash("old-hash");
        stale.setUsedBytes(10L);
        stale.setKnowledgeBytes(3L);
        stale.setStorageInitialized(false);
        stale.setPointsBalance(new BigDecimal("7"));
        when(users.selectById(1L)).thenReturn(stale);
        when(users.selectOne(any())).thenReturn(stale);
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        when(encoder.encode("new-password")).thenReturn("new-hash");
        when(encoder.matches("old-password", "old-hash")).thenReturn(true);
        auth = new AuthService(users, mock(SnowflakeIdGenerator.class), mock(JwtUtils.class), encoder,
                mock(SystemSettingService.class), mock(MailCodeService.class));
        admin = new AdminUserService(users, encoder);
    }

    @Test
    void resetPasswordOnlyUpdatesPassword() {
        auth.resetPassword(new ResetPasswordRequest("old@example.com", "123456", "new-password"));
        assertOnlyColumn("password_hash", "new-hash");
    }

    @Test
    void changePasswordOnlyUpdatesPassword() {
        auth.changePassword(1L, new ChangePasswordRequest("old-password", null, "new-password"));
        assertOnlyColumn("password_hash", "new-hash");
    }

    @Test
    void bindEmailOnlyUpdatesEmail() {
        auth.bindEmail(1L, "new@example.com", "123456");
        assertOnlyColumn("email", "new@example.com");
    }

    @Test
    void adminStatusOnlyUpdatesStatus() {
        admin.setStatus(User.ROLE_OWNER, 1L, User.STATUS_DISABLED);
        assertOnlyColumn("status", User.STATUS_DISABLED);
    }

    @Test
    void adminRoleOnlyUpdatesRole() {
        admin.setRole(1L, User.ROLE_ADMIN);
        assertOnlyColumn("role", User.ROLE_ADMIN);
    }

    @Test
    void adminPasswordOnlyUpdatesPassword() {
        admin.resetPassword(User.ROLE_OWNER, 1L, "new-password");
        assertOnlyColumn("password_hash", "new-hash");
    }

    @Test
    void adminQuotaOnlyUpdatesQuota() {
        admin.setQuota(User.ROLE_OWNER, 1L, 100L);
        assertOnlyColumn("quota_bytes", 100L);
    }

    @Test
    void adminWorkQuotaCanClearOnlyItsOwnColumn() {
        admin.setWorkQuota(User.ROLE_OWNER, 1L, null);
        assertOnlyColumn("work_quota_bytes", null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertOnlyColumn(String column, Object value) {
        ArgumentCaptor<LambdaUpdateWrapper<User>> captor = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(users).update(isNull(), captor.capture());
        LambdaUpdateWrapper<User> update = captor.getValue();
        String set = update.getSqlSet();
        assertTrue(set.startsWith(column + "="), set);
        assertFalse(set.contains(","), set);
        assertTrue(update.getSqlSegment().contains("id ="));
        assertTrue(update.getParamNameValuePairs().containsValue(value));
        assertTrue(update.getParamNameValuePairs().containsValue(1L));
        verify(users, never()).updateById(any(User.class));
    }
}
