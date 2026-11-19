package com.spring.gateway.config;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.InternalAuthInterceptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前端静态资源缓存响应测试。
 *
 * @author hanbing
 * @since 2026-11-19
 */
@WebMvcTest(WebConfigTests.TestController.class)
@AutoConfigureMockMvc
@Import({WebConfig.class, FrontendCacheFilter.class})
class WebConfigTests {

    @RestController
    static class TestController {
    }

    @TempDir
    static Path frontend;

    @DynamicPropertySource
    static void frontendProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.web.resources.static-locations", () -> frontend.toUri().toString());
    }

    @MockitoBean
    private AuthInterceptor authInterceptor;
    @MockitoBean
    private InternalAuthInterceptor internalAuthInterceptor;
    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void createAssets() throws Exception {
        Files.createDirectories(frontend.resolve("js"));
        Files.writeString(frontend.resolve("index.html"), "<h1>home</h1>");
        Files.writeString(frontend.resolve("js/runtime.js"), "console.log('runtime')");
        Files.writeString(frontend.resolve("js/app.abcdef12.js"), "console.log('hashed')");
    }

    @Test
    void hashedAssetIsCachedForSevenDays() throws Exception {
        mockMvc.perform(get("/static/js/app.abcdef12.js"))
                .andExpect(status().isOk())
                .andExpect(content().string("console.log('hashed')"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=604800, public"));
    }

    @Test
    void unhashedAssetsMustRevalidate() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string("<h1>home</h1>"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
        mockMvc.perform(get("/static/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
        mockMvc.perform(get("/static/js/runtime.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
    }
}
