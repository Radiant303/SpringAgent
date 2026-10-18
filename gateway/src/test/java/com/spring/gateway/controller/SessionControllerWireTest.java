package com.spring.gateway.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.dto.SessionSummary;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.service.SessionService;
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
 * 会话接口的 Web 层测试：不需要 MySQL/Redis。覆盖会话写操作改本地落库后的
 * 应答形状（SessionSummary + 统一返回体），以及 history 端点已移除。
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

    @TestConfiguration
    static class WireTestConfig {
        @Bean
        JwtUtils jwtUtils() {
            return new JwtUtils("springagent-dev-secret-0123456789abcd", 10080);
        }

        @Bean
        AuthInterceptor authInterceptor(JwtUtils jwtUtils, ObjectMapper objectMapper) {
            // 拦截器现在按 sub 主键查 users 行收口权限：给测试 token 的 42L 配一个活跃用户
            UserMapper userMapper = Mockito.mock(UserMapper.class);
            User active = new User();
            active.setId(42L);
            active.setRole(User.ROLE_USER);
            active.setStatus(User.STATUS_ACTIVE);
            Mockito.when(userMapper.selectById(42L)).thenReturn(active);
            return new AuthInterceptor(jwtUtils, userMapper, objectMapper);
        }
    }

    private String token() {
        return "Bearer " + new JwtUtils("springagent-dev-secret-0123456789abcd", 10080)
                .createToken(42L, "tester");
    }

    @Test
    @DisplayName("新建会话：本地落库后返回统一返回体包裹的摘要，时间戳补 Z")
    void createReturnsSummary() throws Exception {
        when(sessionService.create(eq(42L), isNull())).thenReturn(new SessionSummary(
                "s1", "w1", "标题",
                LocalDateTime.of(2026, 10, 3, 8, 0, 0),
                LocalDateTime.of(2026, 10, 3, 9, 30, 0, 500000000)));

        mockMvc.perform(post("/api/sessions").header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.session_id").value("s1"))
                .andExpect(jsonPath("$.data.work_id").value("w1"))
                .andExpect(jsonPath("$.data.created_at").value("2026-10-03T08:00:00.000000Z"))
                .andExpect(jsonPath("$.data.updated_at").value("2026-10-03T09:30:00.500000Z"));
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
    @DisplayName("history REST 端点已移除（分页只走 WS 的 session/history）")
    void historyEndpointRemoved() throws Exception {
        mockMvc.perform(get("/api/sessions/s1/history").header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isNotFound());
    }
}
