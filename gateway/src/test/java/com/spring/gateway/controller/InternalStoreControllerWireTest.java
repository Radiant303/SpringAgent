package com.spring.gateway.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.InternalAuthInterceptor;
import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.dto.InternalAppendResult;
import com.spring.gateway.dto.InternalMessage;
import com.spring.gateway.dto.InternalMessagePage;
import com.spring.gateway.entity.Session;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.service.InternalStoreService;
import com.spring.gateway.service.PageMappingService;
import com.spring.gateway.service.WorkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.StringNode;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部接口的 Web 层测试：不依赖 MySQL/Redis，验证路由、令牌鉴权与
 * 响应线格式（裸 JSON、驼峰字段、时间补 Z）。
 *
 * @author hanbing
 * @since 2026-10-03
 */
@WebMvcTest(InternalStoreController.class)
@AutoConfigureMockMvc
@Import(InternalStoreControllerWireTest.WireTestConfig.class)
class InternalStoreControllerWireTest {

    private static final String TOKEN = "springharness-internal-dev-token-0123456789";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private InternalStoreService internalStoreService;
    @MockitoBean
    private WorkService workService;
    @MockitoBean
    private PageMappingService pageMappingService;

    @TestConfiguration
    static class WireTestConfig {
        @Bean
        JwtUtils jwtUtils() {
            return new JwtUtils("springharness-dev-secret-0123456789abcd", 10080);
        }

        @Bean
        AuthInterceptor authInterceptor(JwtUtils jwtUtils, ObjectMapper objectMapper) {
            return new AuthInterceptor(jwtUtils, Mockito.mock(UserMapper.class), objectMapper);
        }

        @Bean
        InternalAuthInterceptor internalAuthInterceptor(ObjectMapper objectMapper) {
            return new InternalAuthInterceptor(TOKEN, objectMapper);
        }
    }

    @Test
    @DisplayName("缺少或错误令牌一律 401")
    void tokenRequired() throws Exception {
        mockMvc.perform(get("/internal/users/7/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/internal/users/7/sessions")
                        .header(InternalAuthInterceptor.HEADER_TOKEN, "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("列表端点返回裸 JSON 数组（调用方直接 response.json() 消费）")
    void listIsBareArray() throws Exception {
        when(internalStoreService.listUserSessions(7L, null)).thenReturn(List.of(session()));
        when(internalStoreService.workspacePath("w1")).thenReturn("/works/w1");

        mockMvc.perform(get("/internal/users/7/sessions")
                        .header(InternalAuthInterceptor.HEADER_TOKEN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].sessionId").value("s1"))
                .andExpect(jsonPath("$[0].userId").value(7))
                .andExpect(jsonPath("$[0].workId").value("w1"))
                .andExpect(jsonPath("$[0].title").value("标题"))
                .andExpect(jsonPath("$[0].workspacePath").value("/works/w1"))
                .andExpect(jsonPath("$[0].currentSegment").value(2))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-03T08:00:00Z"))
                .andExpect(jsonPath("$[0].updatedAt").value("2026-10-03T08:00:00.123456Z"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("详情端点：存在时裸对象，不存在时 404")
    void detail() throws Exception {
        when(internalStoreService.getSession("s1")).thenReturn(session());
        when(internalStoreService.workspacePath("w1")).thenReturn("/works/w1");
        mockMvc.perform(get("/internal/sessions/s1")
                        .header(InternalAuthInterceptor.HEADER_TOKEN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.code").doesNotExist());

        mockMvc.perform(get("/internal/sessions/missing")
                        .header(InternalAuthInterceptor.HEADER_TOKEN, TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("追加消息返回裸对象 {appended, segmentNo}")
    void appendIsBareObject() throws Exception {
        when(internalStoreService.appendMessages(eq("s1"), eq(true), any(), any()))
                .thenReturn(new InternalAppendResult(3, 1));

        mockMvc.perform(post("/internal/sessions/s1/messages")
                        .header(InternalAuthInterceptor.HEADER_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newSegment\":true,\"title\":null,\"messages\":[{\"a\":1},{\"b\":2},{\"c\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appended").value(3))
                .andExpect(jsonPath("$.segmentNo").value(1))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("消息查询返回 {currentSegment, messages:[{segmentNo,payload}]}，payload 原样")
    void messagesShape() throws Exception {
        when(internalStoreService.getMessages("s1")).thenReturn(new InternalMessagePage(
                1, List.of(new InternalMessage(0, new StringNode("hi")))));

        mockMvc.perform(get("/internal/sessions/s1/messages")
                        .header(InternalAuthInterceptor.HEADER_TOKEN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSegment").value(1))
                .andExpect(jsonPath("$.messages[0].segmentNo").value(0))
                .andExpect(jsonPath("$.messages[0].payload").value("hi"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    private static Session session() {
        Session row = new Session();
        row.setId("s1");
        row.setUserId(7L);
        row.setWorkId("w1");
        row.setTitle("标题");
        row.setCurrentSegment(2);
        row.setStatus(Session.STATUS_ACTIVE);
        row.setCreatedAt(LocalDateTime.of(2026, 10, 3, 8, 0, 0));
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 3, 8, 0, 0, 123456000));
        return row;
    }

    @Test
    @DisplayName("work 详情端点带 pageId（雪花 ID 的字符串形）")
    void workDetailHasPageId() throws Exception {
        Work work = new Work();
        work.setId("w1");
        work.setUserId(7L);
        work.setName("默认项目");
        work.setSizeBytes(0L);
        work.setIsDefault(true);
        work.setCreatedAt(LocalDateTime.of(2026, 10, 3, 8, 0, 0));
        work.setUpdatedAt(LocalDateTime.of(2026, 10, 3, 8, 0, 0));
        when(workService.getForInternal("w1")).thenReturn(work);
        when(workService.workspacePath("w1")).thenReturn("/works/w1");
        when(workService.effectiveWorkMaxBytes(work)).thenReturn(0L);
        when(pageMappingService.pageIdOf("w1")).thenReturn(369158185545555999L);

        mockMvc.perform(get("/internal/works/w1")
                        .header(InternalAuthInterceptor.HEADER_TOKEN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workId").value("w1"))
                .andExpect(jsonPath("$.pageId").value("369158185545555999"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
