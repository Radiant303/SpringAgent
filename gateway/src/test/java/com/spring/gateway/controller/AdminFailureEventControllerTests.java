package com.spring.gateway.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.entity.SearchBillingFailure;
import com.spring.gateway.entity.User;
import com.spring.gateway.mapper.SearchBillingFailureMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.service.SearchBillingFailureService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 站长失败事件 HTTP 契约测试。
 *
 * @author hanbing
 * @since 2026-10-18
 */
class AdminFailureEventControllerTests {
    private final SearchBillingFailureMapper mapper = mock(SearchBillingFailureMapper.class);
    private final SearchBillingFailureService failures = mock(SearchBillingFailureService.class);
    private final JwtUtils jwt = mock(JwtUtils.class);
    private final UserMapper users = mock(UserMapper.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SearchBillingFailure.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminFailureEventController(mapper, failures))
                .addInterceptors(new AuthInterceptor(jwt, users, JsonMapper.builder().build())).build();
        when(jwt.parseUserId("owner")).thenReturn(99L);
        User owner = new User(); owner.setId(99L); owner.setRole(User.ROLE_OWNER); owner.setStatus(User.STATUS_ACTIVE);
        when(users.selectById(99L)).thenReturn(owner);
    }

    private SearchBillingFailure row() {
        SearchBillingFailure row = new SearchBillingFailure();
        row.setId(9007199254740993L); row.setStatus("PENDING"); row.setMaxRetries(3); row.setRetryCount(3);
        row.setRawBody(new byte[]{'{', (byte) 0xff}); row.setHeaders("{\"x-death\":[]}");
        row.setErrorMessage("e".repeat(1000)); row.setCreatedAt(LocalDateTime.of(2026, 10, 18, 0, 0));
        row.setUpdatedAt(row.getCreatedAt());
        return row;
    }

    @Test
    @SuppressWarnings("unchecked")
    void clampsPaginationAndReturnsSummaryContract() throws Exception {
        when(mapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<SearchBillingFailure> page = invocation.getArgument(0);
            assertEquals(1, page.getCurrent()); assertEquals(100, page.getSize());
            page.setRecords(List.of(row())); page.setTotal(1);
            return page;
        });
        mvc.perform(get("/api/admin/failure-events?page=0&size=999&status=PENDING&userId=7&eventId=test")
                        .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(100)).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.list[0].maxRetries").value(3))
                .andExpect(jsonPath("$.data.list[0].createdAt").value("2026-10-18T00:00:00Z"))
                .andExpect(jsonPath("$.data.list[0].errorMessage").value("e".repeat(500)))
                .andExpect(jsonPath("$.data.list[0].rawBodyBase64").doesNotExist());
    }

    @Test
    void detailPreservesFullBytesAndError() throws Exception {
        when(mapper.selectById(1L)).thenReturn(row());
        mvc.perform(get("/api/admin/failure-events/1").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rawBodyBase64").value("e/8="))
                .andExpect(jsonPath("$.data.rawBody").isString())
                .andExpect(jsonPath("$.data.errorMessage").value("e".repeat(1000)))
                .andExpect(jsonPath("$.data.headers").value("{\"x-death\":[]}"));
    }

    @Test
    void actorComesFromAuthenticationNotRequestBody() throws Exception {
        mvc.perform(post("/api/admin/failure-events/1/status").header("Authorization", "Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IGNORED\",\"remark\":\"checked\",\"handledBy\":7}"))
                .andExpect(status().isOk());
        verify(failures).changeStatus(1L, "IGNORED", "checked", 99L);
        verifyNoInteractions(mapper);
    }

    @Test
    void onlyOwnerMayReadOrModifyFailureEvents() throws Exception {
        User admin = new User(); admin.setId(8L); admin.setRole(User.ROLE_ADMIN); admin.setStatus(User.STATUS_ACTIVE);
        when(jwt.parseUserId("admin")).thenReturn(8L); when(users.selectById(8L)).thenReturn(admin);
        mvc.perform(get("/api/admin/failure-events").header("Authorization", "Bearer admin"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/failure-events/1").header("Authorization", "Bearer admin"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/failure-events/1/status").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/failure-events")).andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper, failures);
    }
}
