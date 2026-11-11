package com.spring.gateway.workspace.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.UnknownSessionException;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.identity.service.AuthSessionService;
import com.spring.gateway.identity.service.UserProfileService;
import com.spring.gateway.workspace.dto.SessionSummary;
import com.spring.gateway.workspace.service.SessionHistoryService;
import com.spring.gateway.workspace.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话接口的 Web 层测试。
 *
 * @author hanbing
 * @since 2026-10-03
 */
@WebMvcTest(SessionController.class)
@AutoConfigureMockMvc
@Import(SessionControllerWireTest.WireTestConfig.class)
class SessionControllerWireTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private SessionService sessionService;
    @MockitoBean
    private SessionHistoryService sessionHistoryService;

    @TestConfiguration
    static class WireTestConfig {
        @Bean
        AuthSessionService authSessionService() {
            AuthSessionService service = Mockito.mock(AuthSessionService.class);
            Mockito.when(service.authenticate("test-token"))
                    .thenReturn(new AuthSessionService.Authentication(null, 42L));
            Mockito.when(service.authenticate(null))
                    .thenReturn(AuthSessionService.Authentication.invalid());
            return service;
        }

        @Bean
        AuthInterceptor authInterceptor(AuthSessionService authSessionService, ObjectMapper objectMapper) {
            UserProfileService profiles = Mockito.mock(UserProfileService.class);
            User active = new User();
            active.setId(42L);
            active.setRole(User.ROLE_USER);
            active.setStatus(User.STATUS_ACTIVE);
            Mockito.when(profiles.find(42L)).thenReturn(active);
            return new AuthInterceptor(authSessionService, profiles, objectMapper);
        }
    }

    private String token() {
        return "Bearer test-token";
    }

    @Test
    @DisplayName("会话列表：返回 work 过滤后的摘要与运行状态")
    void listReturnsBusy() throws Exception {
        var createdAt = LocalDateTime.of(2026, 10, 3, 8, 0, 0);
        when(sessionService.list(42L, "w1")).thenReturn(java.util.List.of(
                new SessionSummary("s1", "w1", "标题", createdAt, createdAt, true)));

        mockMvc.perform(get("/api/sessions")
                        .param("workId", "w1")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].session_id").value("s1"))
                .andExpect(jsonPath("$.data[0].work_id").value("w1"))
                .andExpect(jsonPath("$.data[0].busy").value(true));
    }

    @Test
    @DisplayName("新建会话：本地落库后返回统一返回体包裹的摘要，时间戳补 Z")
    void createReturnsSummary() throws Exception {
        when(sessionService.create(eq(42L), isNull())).thenReturn(new SessionSummary(
                "s1", "w1", "标题",
                LocalDateTime.of(2026, 10, 3, 8, 0, 0),
                LocalDateTime.of(2026, 10, 3, 9, 30, 0, 500000000), null));

        mockMvc.perform(post("/api/sessions").header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.session_id").value("s1"))
                .andExpect(jsonPath("$.data.work_id").value("w1"))
                .andExpect(jsonPath("$.data.created_at").value("2026-10-03T08:00:00.000000Z"))
                .andExpect(jsonPath("$.data.updated_at").value("2026-10-03T09:30:00.500000Z"))
                .andExpect(jsonPath("$.data.busy").doesNotExist());
    }

    @Test
    @DisplayName("删除会话：成功 200，不存在/越权统一 404")
    void deleteSession() throws Exception {
        doNothing().when(sessionService).delete(42L, "s1");
        doThrow(new BizException(404, "会话不存在")).when(sessionService).delete(42L, "nope");

        mockMvc.perform(delete("/api/sessions/s1").header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/sessions/nope").header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("历史分页：透传游标、页大小和方向，返回统一分页结构")
    void historyReturnsPage() throws Exception {
        var page = new ObjectMapper().createObjectNode();
        page.putArray("segments");
        page.put("previousCursor", "previous-1");
        page.put("hasMore", true);
        when(sessionHistoryService.queryHistory("s1", 42L, "cursor-1", 30, "backward")).thenReturn(page);

        mockMvc.perform(get("/api/sessions/s1/history")
                        .param("cursor", "cursor-1")
                        .param("limit", "30")
                        .param("direction", "backward")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.segments").isArray())
                .andExpect(jsonPath("$.data.previousCursor").value("previous-1"))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }

    @Test
    @DisplayName("历史分页：未知会话返回 404，非法游标返回 400")
    void historyErrorsUseHttpStatus() throws Exception {
        when(sessionHistoryService.queryHistory("missing", 42L, null, null, "forward"))
                .thenThrow(new UnknownSessionException("missing"));
        when(sessionHistoryService.queryHistory("s1", 42L, "bad", 30, "backward"))
                .thenThrow(new IllegalArgumentException("invalid history cursor"));

        mockMvc.perform(get("/api/sessions/missing/history").header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/sessions/s1/history")
                        .param("cursor", "bad")
                        .param("limit", "30")
                        .param("direction", "backward")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("历史分页：未认证请求返回 401")
    void historyRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/sessions/s1/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
