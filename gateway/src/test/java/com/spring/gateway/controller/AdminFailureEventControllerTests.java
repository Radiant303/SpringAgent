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
    void modelDetailExposesSettlementAndUnknownIsNotPaid() throws Exception {
        SearchBillingFailure row = row();
        row.setFailureType("MODEL_SETTLE"); row.setRequestId("0123456789abcdef0123456789abcdef");
        row.setModelId("model"); row.setSettlementStatus("UNKNOWN"); row.setDeliveryStatus("DELIVERED");
        row.setMaxRetries(null); row.setRetryCount(0); row.setHeaders("{}");
        row.setRawBody(("{\"requestId\":\"" + row.getRequestId() + "\",\"userId\":7,\"status\":\"error\","
                + "\"usageKnown\":false,\"inputTokens\":0,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,"
                + "\"outputTokens\":0,\"webSearches\":null}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(mapper.selectById(1L)).thenReturn(row);
        mvc.perform(get("/api/admin/failure-events/1").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failureType").value("MODEL_SETTLE"))
                .andExpect(jsonPath("$.data.requestId").value(row.getRequestId()))
                .andExpect(jsonPath("$.data.modelId").value("model"))
                .andExpect(jsonPath("$.data.settlementStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.maxRetries").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.completedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.pointsReference").value(true))
                .andExpect(jsonPath("$.data.settlement.usageKnown").value(false))
                .andExpect(jsonPath("$.data.settlement.inputTokens").value(0))
                .andExpect(jsonPath("$.data.settlement.webSearches").value(org.hamcrest.Matchers.nullValue()));
        row.setSettlementStatus("SETTLED"); row.setStatus("RESOLVED");
        assertEquals(true, AdminFailureEventController.view(row, false).get("pointsReference"));
        row.setStatus("SUCCEEDED");
        assertEquals(false, AdminFailureEventController.view(row, false).get("pointsReference"));
        row.setSettlementStatus("DISCARDED");
        assertEquals(true, AdminFailureEventController.view(row, false).get("pointsReference"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void optionalFailureTypeFilterUsesTypedColumn() throws Exception {
        when(mapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            var filter = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SearchBillingFailure>) invocation.getArgument(1);
            assertTrue(filter.getSqlSegment().contains("failure_type"));
            assertTrue(filter.getParamNameValuePairs().containsValue("MODEL_SETTLE"));
            return invocation.getArgument(0);
        });
        mvc.perform(get("/api/admin/failure-events?failureType=MODEL_SETTLE").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk());
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
    void internalFailureReportUsesExistingAuthAndNeverSettles() throws Exception {
        var billing = mock(com.spring.gateway.service.BillingService.class);
        var reports = mock(com.spring.gateway.service.ModelBillingFailureService.class);
        MockMvc internal = MockMvcBuilders.standaloneSetup(new InternalBillingController(billing, reports))
                .addInterceptors(new com.spring.gateway.common.InternalAuthInterceptor("test-token",
                        JsonMapper.builder().build())).build();
        String body = "{\"requestId\":\"0123456789abcdef0123456789abcdef\",\"userId\":7,"
                + "\"settlement\":{\"requestId\":\"0123456789abcdef0123456789abcdef\",\"userId\":7,"
                + "\"status\":\"error\",\"usageKnown\":false,\"inputTokens\":0,\"cacheReadTokens\":0,"
                + "\"cacheWriteTokens\":0,\"outputTokens\":0,\"webSearches\":null},\"deliveryStatus\":\"DELIVERED\","
                + "\"failureCount\":0,\"firstFailedAt\":\"2026-10-19T00:00:00Z\",\"lastFailedAt\":\"2026-10-19T00:01:00Z\","
                + "\"errorType\":\"UsageUnknown\",\"errorMessage\":\"unknown\"}";
        internal.perform(post("/internal/billing/requests/failure").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        internal.perform(post("/internal/billing/requests/failure").header("X-Internal-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        when(reports.report(any())).thenReturn("PENDING");
        internal.perform(post("/internal/billing/requests/failure").header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        for (String invalid : new String[]{body.replace("\"failureCount\":0", "\"failureCount\":-1"),
                body.replace("DELIVERED", "SUCCESS"), body.replace("\"inputTokens\":0", "\"inputTokens\":-1")}) {
            internal.perform(post("/internal/billing/requests/failure").header("X-Internal-Token", "test-token")
                            .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        verify(reports).report(argThat(request -> request.failureCount() == 0 && request.settlement().webSearches() == null));
        verifyNoInteractions(billing);
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
