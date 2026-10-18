package com.spring.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部模型计费接口验证。
 *
 * @author hanbing
 * @since 2026-10-13
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalBillingControllerTests {
    @Autowired private MockMvc mvc;
    @Value("${app.internal-token}") private String internalToken;

    private static final String USAGE = "{\"requestId\":\"0123456789abcdef0123456789abcdef\","
            + "\"userId\":1,\"status\":\"finished\",\"usageKnown\":true,\"inputTokens\":100,"
            + "\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"outputTokens\":20}";

    @Test
    void requiresInternalAuthentication() throws Exception {
        for (String operation : new String[]{"reserve", "settle", "failure"}) {
            mvc.perform(post("/internal/billing/requests/" + operation)
                            .contentType(MediaType.APPLICATION_JSON).content(USAGE))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/billing/requests/" + operation)
                            .header("X-Internal-Token", "wrong")
                            .contentType(MediaType.APPLICATION_JSON).content(USAGE))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void rejectsInvalidTokensAndStatusBeforeBilling() throws Exception {
        for (String body : new String[]{USAGE.replace("\"inputTokens\":100", "\"inputTokens\":-1"),
                USAGE.replace("finished", "bogus"), USAGE.replace("\"outputTokens\":20", "\"outputTokens\":null")}) {
            mvc.perform(post("/internal/billing/requests/settle")
                            .header("X-Internal-Token", internalToken)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsNegativeSearchCountBeforeBilling() throws Exception {
        mvc.perform(post("/internal/billing/requests/settle")
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(USAGE.replace("\"outputTokens\":20", "\"outputTokens\":20,\"webSearches\":-1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsNullableAndNonnegativeSearchCounts() throws Exception {
        for (String searches : new String[]{"null", "0", "2"}) {
            mvc.perform(post("/internal/billing/requests/settle")
                            .header("X-Internal-Token", internalToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(USAGE.replace("\"outputTokens\":20",
                                    "\"outputTokens\":20,\"webSearches\":" + searches)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void refusesUsageWithoutReservation() throws Exception {
        mvc.perform(post("/internal/billing/requests/settle")
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(USAGE))
                .andExpect(status().isNotFound());
    }
}
