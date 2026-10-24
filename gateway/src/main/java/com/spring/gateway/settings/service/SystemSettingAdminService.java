package com.spring.gateway.settings.service;

import com.spring.gateway.settings.dto.AdminBillingEstRequest;
import com.spring.gateway.settings.dto.AdminCommunityLinkRequest;
import com.spring.gateway.settings.dto.AdminMailSettingsRequest;
import com.spring.gateway.settings.dto.AdminPlatformSettingsRequest;
import com.spring.gateway.settings.dto.AdminRegistrationRequest;
import com.spring.gateway.settings.dto.AdminSearchSettingsRequest;
import com.spring.gateway.settings.dto.AdminStorageSettingsRequest;
import com.spring.gateway.settings.dto.AdminWorkMaxBytesRequest;

import java.util.Map;

/**
 * 系统设置管理业务。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public interface SystemSettingAdminService {
    Map<String, Object> snapshot();

    void setSearch(AdminSearchSettingsRequest req);

    void setRegistration(AdminRegistrationRequest req);

    void setWorkMaxBytes(AdminWorkMaxBytesRequest req);

    long sendMailDisableCode(long userId);

    void setMail(long userId, AdminMailSettingsRequest req);

    void setCommunityLink(AdminCommunityLinkRequest req);

    void setPlatform(AdminPlatformSettingsRequest req);

    void setStorage(AdminStorageSettingsRequest req);

    void setBillingEst(AdminBillingEstRequest req);
}
