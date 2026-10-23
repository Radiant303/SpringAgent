package com.spring.gateway.controller;

import com.spring.gateway.service.AuthSessionService;
import com.spring.gateway.common.SearchProviders;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.SystemSettingMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜索设置权限与参数校验测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminSearchSettingsTests {
    @Autowired private MockMvc mvc;
    @Autowired private AuthSessionService jwt;
    @Autowired private SnowflakeIdGenerator ids;
    @Autowired private UserMapper users;
    @Autowired private SystemSettingMapper settings;
    @Autowired private SystemSettingService service;
    private String owner;

    @BeforeEach
    void fixture() {
        owner = bearer(User.ROLE_OWNER);
        settings.deleteById(SearchProviders.LOCAL_ENABLED_KEY);
        settings.deleteById(SearchProviders.LOCAL_PROVIDER_KEY);
        settings.deleteById(SearchProviders.NATIVE_POINTS_KEY);
        SearchProviders.ALL.keySet().forEach(id -> {
            settings.deleteById(SearchProviders.apiKeySetting(id));
            settings.deleteById(SearchProviders.pointsSetting(id));
        });
    }

    private String bearer(String role) {
        User user = new User();
        user.setId(ids.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setRole(role);
        user.setStatus(User.STATUS_ACTIVE);
        user.setPointsBalance(new BigDecimal("100"));
        user.setQuotaBytes(0L);
        users.insert(user);
        return "Bearer " + jwt.createSession(user.getId()).token();
    }

    private String request(boolean enabled, String provider, String nativePoints, String providers) {
        return "{\"localEnabled\":" + enabled + ",\"localProvider\":\"" + provider
                + "\",\"nativePoints\":\"" + nativePoints + "\",\"providers\":" + providers + "}";
    }

    private void save(String bearer, String body, int status) throws Exception {
        mvc.perform(post("/api/admin/settings/search").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status));
    }

    @Test
    void defaultsAreDisabledAndOnePoint() throws Exception {
        mvc.perform(get("/api/admin/settings").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.localEnabled").value(false))
                .andExpect(jsonPath("$.data.search.localProvider").value("tavily"))
                .andExpect(jsonPath("$.data.search.nativePoints").value("1"))
                .andExpect(jsonPath("$.data.search.providers.tavily.label").value("Tavily"))
                .andExpect(jsonPath("$.data.search.providers.exa.label").value("Exa"))
                .andExpect(jsonPath("$.data.search.providers.tavily.points").value("1"))
                .andExpect(jsonPath("$.data.search.providers.exa.points").value("1"))
                .andExpect(jsonPath("$.data.search.providers.tavily.apiKeyConfigured").value(false));
        assertEquals(BigDecimal.ONE, service.getSearchPoints("native"));
        assertEquals(BigDecimal.ONE, service.getSearchPoints("tavily"));
        assertEquals(BigDecimal.ONE, service.getSearchPoints("exa"));
        assertTrue(SearchProviders.isSupported("tavily"));
        assertFalse(SearchProviders.isSupported("native"));
        assertThrows(UnsupportedOperationException.class, () -> SearchProviders.ALL.put("other", "Other"));
    }

    @Test
    void onlyOwnerCanWriteAndStaffCanRead() throws Exception {
        String body = request(true, "tavily", "1", "{}");
        mvc.perform(post("/api/admin/settings/search").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String admin = bearer(User.ROLE_ADMIN);
        String user = bearer(User.ROLE_USER);
        save(admin, body, 403);
        save(user, body, 403);
        mvc.perform(get("/api/admin/settings").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/settings").header("Authorization", user)).andExpect(status().isForbidden());
        save(owner, body, 200);
    }

    @Test
    void enabledWithoutKeyIsAllowedAndNativePointsIgnoreSwitch() throws Exception {
        save(owner, request(true, "exa", "0.123456", "{\"exa\":{\"points\":\"0\"}}"), 200);
        assertEquals("true", service.getString(SearchProviders.LOCAL_ENABLED_KEY, ""));
        assertEquals("", service.getString(SearchProviders.apiKeySetting("exa"), ""));
        assertEquals(new BigDecimal("0.123456"), service.getSearchPoints("native"));
        assertEquals(BigDecimal.ZERO, service.getSearchPoints("exa"));
        save(owner, request(false, "exa", "0.123456", "{}"), 200);
        assertEquals(new BigDecimal("0.123456"), service.getSearchPoints("native"));
    }

    @Test
    void keysAreWriteOnlyAndBlankPreservesWhileNonblankReplaces() throws Exception {
        String keys = "{\"tavily\":{\"apiKey\":\"secret-tavily\",\"points\":\"1.000001\"},"
                + "\"exa\":{\"apiKey\":\"secret-exa\",\"points\":\"2\"}}";
        save(owner, request(false, "tavily", "1", keys), 200);
        save(owner, request(false, "exa", "1", "{\"tavily\":{\"apiKey\":\"\",\"points\":\"3\"},"
                + "\"exa\":{\"apiKey\":null,\"points\":\"2\"}}"), 200);
        assertEquals("secret-tavily", service.getString(SearchProviders.apiKeySetting("tavily"), ""));
        assertEquals("secret-exa", service.getString(SearchProviders.apiKeySetting("exa"), ""));
        save(owner, request(false, "exa", "1", "{\"tavily\":{\"apiKey\":\"   \",\"points\":\"3\"},"
                + "\"exa\":{\"apiKey\":\" \\t\\r\\n \",\"points\":\"2\"}}"), 200);
        assertEquals("secret-tavily", service.getString(SearchProviders.apiKeySetting("tavily"), ""));
        assertEquals("secret-exa", service.getString(SearchProviders.apiKeySetting("exa"), ""));
        save(owner, request(false, "exa", "1", "{\"exa\":{\"apiKey\":\" secret-exa-replaced \","
                + "\"points\":\"2\"}}"), 200);
        assertEquals("secret-exa-replaced", service.getString(SearchProviders.apiKeySetting("exa"), ""));
        assertEquals("secret-tavily", service.getString(SearchProviders.apiKeySetting("tavily"), ""));
        for (String reader : new String[]{owner, bearer(User.ROLE_ADMIN)}) {
            mvc.perform(get("/api/admin/settings").header("Authorization", reader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.search.providers.tavily.apiKeyConfigured").value(true))
                    .andExpect(jsonPath("$.data.search.providers.exa.apiKeyConfigured").value(true))
                    .andExpect(jsonPath("$.data.search.providers.tavily.apiKey").doesNotExist())
                    .andExpect(jsonPath("$.data.search.providers.exa.apiKey").doesNotExist())
                    .andExpect(content().string(not(containsString("secret-"))));
        }
    }

    @Test
    void rejectsUnknownProviderInSelectionAndMap() throws Exception {
        save(owner, request(false, "other", "1", "{}"), 400);
        save(owner, request(false, "tavily", "1", "{\"other\":{\"points\":\"1\"}}"), 400);
        save(owner, request(false, "tavily", "1", "{\"native\":{\"points\":\"1\"}}"), 400);
    }

    @Test
    void acceptsSixDecimalsAndMaximumKeyLength() throws Exception {
        String key = "k".repeat(1024);
        save(owner, request(false, "tavily", "1000000", "{\"tavily\":{\"apiKey\":\"" + key
                + "\",\"points\":\"0.000001\"}}"), 200);
        assertEquals(key, service.getString(SearchProviders.apiKeySetting("tavily"), ""));
        assertEquals(new BigDecimal("0.000001"), service.getSearchPoints("tavily"));
        mvc.perform(get("/api/admin/settings").header("Authorization", owner))
                .andExpect(jsonPath("$.data.search.nativePoints").value("1000000"))
                .andExpect(jsonPath("$.data.search.providers.tavily.points").value("0.000001"));
    }

    @Test
    void rejectsInvalidPointsAndOversizedKeys() throws Exception {
        for (String points : new String[]{"-1", "0.0000001", "1000001"}) {
            save(owner, request(false, "tavily", points, "{}"), 400);
            save(owner, request(false, "tavily", "1", "{\"exa\":{\"points\":\"" + points + "\"}}"), 400);
        }
        save(owner, request(false, "tavily", "1", "{\"tavily\":{\"apiKey\":\""
                + "k".repeat(1025) + "\",\"points\":\"1\"}}"), 400);
        save(owner, request(false, "tavily", "1", "{\"exa\":null}"), 400);
        save(owner, request(false, "tavily", "1", "{\"exa\":{}}"), 400);
    }

    @Test
    void validatesAllProvidersBeforeWritingAnySetting() throws Exception {
        service.set(SearchProviders.LOCAL_ENABLED_KEY, "false");
        service.set(SearchProviders.LOCAL_PROVIDER_KEY, "tavily");
        service.set(SearchProviders.NATIVE_POINTS_KEY, "2");
        service.set(SearchProviders.apiKeySetting("tavily"), "original-key");
        service.set(SearchProviders.pointsSetting("tavily"), "3");
        save(owner, request(true, "exa", "4", "{\"tavily\":{\"apiKey\":\"new-key\",\"points\":\"5\"},"
                + "\"exa\":{\"points\":\"0.0000001\"}}"), 400);
        assertEquals("false", service.getString(SearchProviders.LOCAL_ENABLED_KEY, ""));
        assertEquals("tavily", service.getString(SearchProviders.LOCAL_PROVIDER_KEY, ""));
        assertEquals(new BigDecimal("2"), service.getSearchPoints("native"));
        assertEquals("original-key", service.getString(SearchProviders.apiKeySetting("tavily"), ""));
        assertEquals(new BigDecimal("3"), service.getSearchPoints("tavily"));
    }
}
