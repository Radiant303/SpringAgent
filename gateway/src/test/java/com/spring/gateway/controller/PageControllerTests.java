package com.spring.gateway.controller;

import com.spring.gateway.entity.PageMapping;
import com.spring.gateway.service.PageMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目页面分发接口测试：路由补斜杠、index.html 补全、路径穿越与后缀白名单。
 *
 * @author hanbing
 * @since 2026-10-16
 */
class PageControllerTests {

    @TempDir
    Path dataRoot;

    private MockMvc mockMvc;
    private PageMappingService pageMappingService;

    private static final long PAGE_ID = 369158185545555999L;
    private static final String WORK_ID = "w-test";

    @BeforeEach
    void setUp() throws Exception {
        pageMappingService = Mockito.mock(PageMappingService.class);
        PageController controller = new PageController(pageMappingService);
        ReflectionTestUtils.setField(controller, "dataRoot", dataRoot.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        PageMapping mapping = new PageMapping();
        mapping.setId(PAGE_ID);
        mapping.setWorkId(WORK_ID);
        when(pageMappingService.find(PAGE_ID)).thenReturn(mapping);

        Path page = dataRoot.resolve("works/" + WORK_ID + "/page");
        Files.createDirectories(page.resolve("css"));
        Files.createDirectories(page.resolve("blog"));
        Files.writeString(page.resolve("index.html"), "<h1>home</h1>");
        Files.writeString(page.resolve("css/style.css"), "body{}");
        Files.writeString(page.resolve("blog/index.html"), "<h1>blog</h1>");
    }

    @Test
    @DisplayName("无结尾斜杠一律 301 补斜杠")
    void redirectAddsTrailingSlash() throws Exception {
        mockMvc.perform(get("/page/{id}", PAGE_ID))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/page/" + PAGE_ID + "/"));
    }

    @Test
    @DisplayName("目录路径补 index.html，HTML 带 no-cache")
    void indexHtmlServed() throws Exception {
        mockMvc.perform(get("/page/{id}/", PAGE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string("<h1>home</h1>"));
        mockMvc.perform(get("/page/{id}/blog/", PAGE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string("<h1>blog</h1>"));
    }

    @Test
    @DisplayName("静态资源按后缀白名单分发，同样带 no-cache")
    void staticAssetServed() throws Exception {
        mockMvc.perform(get("/page/{id}/css/style.css", PAGE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/css;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string("body{}"));
    }

    @Test
    @DisplayName("经 Nginx（X-Accel-Capable）时指路 /_internal/")
    void accelRedirect() throws Exception {
        mockMvc.perform(get("/page/{id}/css/style.css", PAGE_ID)
                        .header("X-Accel-Capable", "nginx"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect",
                        "/_internal/works/" + WORK_ID + "/page/css/style.css"))
                .andExpect(content().string(""));
        mockMvc.perform(get("/page/{id}/", PAGE_ID)
                        .header("X-Accel-Capable", "nginx"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect",
                        "/_internal/works/" + WORK_ID + "/page/index.html"))
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    @DisplayName("路径穿越、非法段、白名单外后缀一律 404")
    void traversalAndWhitelistRejected() throws Exception {
        mockMvc.perform(get("/page/{id}/../secret.txt", PAGE_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/page/{id}/%2e%2e/secret.txt", PAGE_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/page/{id}/evil.php", PAGE_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/page/{id}/run.sh", PAGE_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/page/{id}/README", PAGE_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/page/{id}/有空 格.html", PAGE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("超出 long 上限的页面 ID 走友好 404，而不是参数类型错误")
    void overflowPageIdIs404() throws Exception {
        mockMvc.perform(get("/page/9999999999999999999/"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("页面不存在")));
        mockMvc.perform(get("/page/9999999999999999999"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/page/9999999999999999999/"));
    }

    @Test
    @DisplayName("页面根被链接替换时返回友好 404")
    void rejectsLinkedPageRoot() throws Exception {
        Path page = dataRoot.resolve("works/" + WORK_ID + "/page");
        Files.move(page, page.resolveSibling("original-page"));
        Path secrets = Files.createDirectories(dataRoot.resolve(".secrets"));
        Files.writeString(secrets.resolve("index.html"), "fixture-only");
        createDirectoryLink(page, secrets);
        assertRejected("/");
    }

    @Test
    @DisplayName("工作区祖先被链接替换时返回友好 404")
    void rejectsLinkedWorkAncestor() throws Exception {
        Path work = dataRoot.resolve("works/" + WORK_ID);
        Path moved = Files.move(work, dataRoot.resolve("moved-work"));
        createDirectoryLink(work, moved);
        assertRejected("/");
    }

    @Test
    @DisplayName("数据根自身或祖先是链接时拒绝页面分发")
    void rejectsLinkedDataRootAndAncestor() throws Exception {
        Path realRoot = Files.createDirectories(dataRoot.resolve("separate-root"));
        Path nestedRoot = realRoot.resolve("nested");
        Path realPage = Files.createDirectories(nestedRoot.resolve("works/" + WORK_ID + "/page"));
        Files.writeString(realPage.resolve("index.html"), "fixture-only");
        Path alias = dataRoot.resolve("alias");
        createDirectoryLink(alias, nestedRoot);
        Path parentAlias = dataRoot.resolve("parent-alias");
        createDirectoryLink(parentAlias, realRoot);
        PageController controller = new PageController(pageMappingService);
        for (Path root : new Path[]{alias, parentAlias.resolve("nested")}) {
            ReflectionTestUtils.setField(controller, "dataRoot", root.toString());
            MockMvcBuilders.standaloneSetup(controller).build().perform(get("/page/{id}/", PAGE_ID))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("页面子目录链接不能分发秘密文件")
    void rejectsLinkedTargetDirectory() throws Exception {
        Path secrets = Files.createDirectories(dataRoot.resolve(".secrets"));
        Files.writeString(secrets.resolve("fixture.txt"), "fixture-only");
        Path link = dataRoot.resolve("works/" + WORK_ID + "/page/leak");
        createDirectoryLink(link, secrets);
        assertRejected("/leak/fixture.txt");
    }

    @Test
    @DisplayName("页面文件链接不能分发秘密文件")
    void rejectsLinkedTargetFile() throws Exception {
        Path secret = Files.writeString(dataRoot.resolve("fixture.txt"), "fixture-only");
        Path link = dataRoot.resolve("works/" + WORK_ID + "/page/leak.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("文件符号链接不可用");
        }
        assertRejected("/leak.txt");
    }

    private void assertRejected(String suffix) throws Exception {
        for (boolean accel : new boolean[]{false, true}) {
            var request = get("/page/" + PAGE_ID + suffix);
            if (accel) {
                request.header("X-Accel-Capable", "nginx");
            }
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist("X-Accel-Redirect"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("页面不存在")));
        }
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            org.junit.jupiter.api.Assertions.assertEquals(0, process.waitFor(), "创建测试 junction 失败");
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    @Test
    @DisplayName("文件不存在或映射不存在返回友好 404 页")
    void friendlyNotFound() throws Exception {
        mockMvc.perform(get("/page/{id}/missing.html", PAGE_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("页面不存在")));
        mockMvc.perform(get("/page/{id}/", 1L))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("页面不存在")));
    }
}
