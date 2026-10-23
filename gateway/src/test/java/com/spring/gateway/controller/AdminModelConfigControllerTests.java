package com.spring.gateway.controller;

import com.spring.gateway.service.AuthSessionService;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模型配置接口集成测试：鉴权分层（无 token 401 / 普通用户 403 / 管理员可读不可写 / 站长全量）。
 *
 * @author hanbing
 * @since 2026-10-09
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminModelConfigControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthSessionService jwtUtils;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SnowflakeIdGenerator idGenerator;

    /** 插入一个指定角色的用户并签发 token。 */
    private String tokenFor(String role) {
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setRole(role);
        user.setStatus(User.STATUS_ACTIVE);
        userMapper.insert(user);
        return jwtUtils.createSession(user.getId()).token();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    @DisplayName("鉴权分层：无 token 401；普通用户 403；管理员可读不可写；站长可写")
    void authLayers() throws Exception {
        mockMvc.perform(get("/api/admin/model-config"))
                .andExpect(status().isUnauthorized());

        String userToken = tokenFor(User.ROLE_USER);
        mockMvc.perform(get("/api/admin/model-config")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());

        String adminToken = tokenFor(User.ROLE_ADMIN);
        mockMvc.perform(get("/api/admin/model-config")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providers").isArray())
                .andExpect(jsonPath("$.data.models").isArray());
        mockMvc.perform(post("/api/admin/model-config/default")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"x/y\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("仅站长可管理模型配置"));

        String ownerToken = tokenFor(User.ROLE_OWNER);
        mockMvc.perform(post("/api/admin/model-config/default")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"x/y\"}"))
                .andExpect(status().isBadRequest());  // 站长通过鉴权，业务校验拒绝不存在的模型
    }

    @Test
    @DisplayName("模型能力：拒绝已移除的能力和未知能力")
    void rejectsUnsupportedCapabilities() throws Exception {
        String token = tokenFor(User.ROLE_OWNER);
        for (String capability : new String[]{"always_thinking", "audio_in", "video_in", "unknown", "", " "}) {
            mockMvc.perform(post("/api/admin/model-config/models")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"test\",\"model\":\"model\",\"displayName\":\"测试模型\","
                                    + "\"maxContextSize\":1000000,\"maxOutputSize\":0,"
                                    + "\"capabilities\":[\"" + capability + "\"],\"supportEfforts\":[],"
                                    + "\"defaultEffort\":\"\",\"enabled\":true}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("capabilities[0]: 不支持的模型能力"));
        }
    }

    @Test
    @DisplayName("接口拒绝无效思考挡位")
    void rejectsUnsupportedEfforts() throws Exception {
        String token = tokenFor(User.ROLE_OWNER);
        for (String effort : new String[]{"max", "HIGH", "", " high", "custom"}) {
            mockMvc.perform(post("/api/admin/model-config/models")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"test\",\"model\":\"model\",\"displayName\":\"测试模型\","
                                    + "\"maxContextSize\":1000,\"maxOutputSize\":0,"
                                    + "\"capabilities\":[\"thinking\"],"
                                    + "\"defaultEffort\":\"" + effort + "\",\"enabled\":true}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("站长全链路：建 Provider → 建模型 → 设默认 → GET 快照 → 删默认被拒")
    void ownerFullFlow() throws Exception {
        String token = tokenFor(User.ROLE_OWNER);
        String name = "t" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/admin/model-config/providers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"openai\","
                                + "\"apiKey\":\"sk-test\",\"baseUrl\":\"https://api.example.com\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/model-config/models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"" + name + "\","
                                + "\"model\":\"real-model\",\"displayName\":\"展示名\","
                                + "\"maxContextSize\":1000000,\"maxOutputSize\":0,"
                                + "\"capabilities\":[\"thinking\",\"native_search\"],\"supportEfforts\":[\"high\"],"
                                + "\"defaultEffort\":\"high\",\"enabled\":true}"))
                .andExpect(status().isOk());

        String modelId = name + "/real-model";
        mockMvc.perform(post("/api/admin/model-config/default")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"" + modelId + "\"}"))
                .andExpect(status().isOk());

        // GET 快照：api_key 不出现在响应里，只有 api_key_configured 标记
        mockMvc.perform(get("/api/admin/model-config")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultModel").value(modelId))
                .andExpect(jsonPath("$.data.providers[?(@.name=='" + name + "')].api_key_configured").value(true));

        // 默认模型不可删
        mockMvc.perform(post("/api/admin/model-config/models/delete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"" + modelId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("子代理模型：可配可读；未知名/不存在模型 400；被引用时模型不可删")
    void subagentModelsFlow() throws Exception {
        String token = tokenFor(User.ROLE_OWNER);
        String name = "t" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/admin/model-config/providers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"openai\","
                                + "\"apiKey\":\"sk-test\",\"baseUrl\":\"https://api.example.com\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/model-config/models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"" + name + "\","
                                + "\"model\":\"sub-model\",\"displayName\":\"展示名\","
                                + "\"maxContextSize\":1000000,\"maxOutputSize\":0,"
                                + "\"capabilities\":[],\"supportEfforts\":[],"
                                + "\"defaultEffort\":\"\",\"enabled\":true}"))
                .andExpect(status().isOk());
        String modelId = name + "/sub-model";

        // 未知子代理名 / 不存在的模型都 400
        mockMvc.perform(post("/api/admin/model-config/subagent-models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"models\":{\"hacker\":\"" + modelId + "\"}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/model-config/subagent-models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"models\":{\"searcher\":\"no/such-model\"}}"))
                .andExpect(status().isBadRequest());

        // 配置成功：searcher 用指定模型，reviewer 留空 = 继承主模型
        mockMvc.perform(post("/api/admin/model-config/subagent-models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"models\":{\"searcher\":\"" + modelId + "\",\"reviewer\":\"\"}}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/model-config")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subagentModels.searcher").value(modelId))
                .andExpect(jsonPath("$.data.subagentModels.reviewer").value(""));

        // 被子代理引用的模型不可删；改回继承后可删
        mockMvc.perform(post("/api/admin/model-config/models/delete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"" + modelId + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/admin/model-config/subagent-models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"models\":{\"searcher\":\"\"}}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/model-config/models/delete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"" + modelId + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("新端点鉴权：available-models / models/test 无 token 401、普通用户 403、管理员 403、站长通过鉴权")
    void newEndpointsAuth() throws Exception {
        mockMvc.perform(get("/api/admin/model-config/providers/no-such/available-models"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/model-config/models/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"no/such-model\"}"))
                .andExpect(status().isUnauthorized());

        String userToken = tokenFor(User.ROLE_USER);
        mockMvc.perform(get("/api/admin/model-config/providers/no-such/available-models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/model-config/models/test")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"no/such-model\"}"))
                .andExpect(status().isForbidden());

        String adminToken = tokenFor(User.ROLE_ADMIN);
        mockMvc.perform(get("/api/admin/model-config/providers/no-such/available-models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("仅站长可管理模型配置"));

        String ownerToken = tokenFor(User.ROLE_OWNER);
        // 站长通过鉴权；Provider/模型不存在，业务层 404
        mockMvc.perform(get("/api/admin/model-config/providers/no-such/available-models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/model-config/models/test")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"no/such-model\"}"))
                .andExpect(status().isNotFound());
    }
}
