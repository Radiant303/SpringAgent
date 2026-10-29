package com.spring.gateway.knowledge.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.GlobalExceptionHandler;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.identity.service.AuthSessionService;
import com.spring.gateway.identity.service.impl.UserProfileServiceImpl;
import com.spring.gateway.knowledge.service.impl.KnowledgeServiceImpl;
import com.spring.gateway.storage.mapper.FileRecordMapper;
import com.spring.gateway.storage.service.impl.UserStorageServiceImpl;
import com.spring.gateway.workspace.mapper.SessionMapper;
import com.spring.gateway.workspace.mapper.WorkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库接口 HTTP 契约测试。
 *
 * @author hanbing
 * @since 2026-10-29
 */
class KnowledgeControllerTests {
    private static final long USER_ID = 7L;

    @TempDir Path dataRoot;

    private final AuthSessionService jwt = mock(AuthSessionService.class);
    private final UserMapper users = mock(UserMapper.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        UserStorageServiceImpl storage = new UserStorageServiceImpl(
                mock(WorkMapper.class), mock(SessionMapper.class), mock(FileRecordMapper.class),
                users, mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(storage, "dataRoot", dataRoot.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(new KnowledgeServiceImpl(storage)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor(jwt, new UserProfileServiceImpl(users), JsonMapper.builder().build()))
                .build();
        when(jwt.authenticate(nullable(String.class))).thenReturn(AuthSessionService.Authentication.invalid());
        when(jwt.authenticate("user")).thenReturn(AuthSessionService.Authentication.valid(USER_ID));
        User current = new User();
        current.setId(USER_ID);
        current.setRole(User.ROLE_USER);
        current.setStatus(User.STATUS_ACTIVE);
        when(users.selectById(USER_ID)).thenReturn(current);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/knowledge"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsOnlyCurrentUsersDocuments() throws Exception {
        writeDocument(USER_ID, "declarative.md", "## Java\n\n当前用户的知识");
        writeDocument(USER_ID, "conditional.md", "适用条件");
        writeDocument(8L, "declarative.md", "其他用户的知识");

        mockMvc.perform(get("/api/knowledge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].type").value("declarative"))
                .andExpect(jsonPath("$.data[0].content").value("## Java\n\n当前用户的知识"))
                .andExpect(jsonPath("$.data[1].type").value("procedural"))
                .andExpect(jsonPath("$.data[1].content").value(""))
                .andExpect(jsonPath("$.data[2].type").value("conditional"))
                .andExpect(jsonPath("$.data[2].content").value("适用条件"));
    }

    @Test
    void missingDocumentsAreEmptyWithoutCreatingDirectories() throws Exception {
        mockMvc.perform(get("/api/knowledge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].content").value(""))
                .andExpect(jsonPath("$.data[1].content").value(""))
                .andExpect(jsonPath("$.data[2].content").value(""));

        assertFalse(Files.exists(dataRoot.resolve("users").resolve(Long.toString(USER_ID))));
    }

    private void writeDocument(long userId, String name, String content) throws Exception {
        Path directory = dataRoot.resolve("users").resolve(Long.toString(userId)).resolve("knowledge");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
    }
}
