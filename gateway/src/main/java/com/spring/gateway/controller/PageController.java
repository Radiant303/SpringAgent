package com.spring.gateway.controller;

import com.spring.gateway.entity.PageMapping;
import com.spring.gateway.service.PageMappingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 项目页面访问：GET /page/{pageId}/... 的静态文件分发，无需登录。
 *
 * @author hanbing
 * @since 2026-10-16
 */
@RestController
@RequiredArgsConstructor
public class PageController {

    /** 文件与目录名的合法字符集 */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private static final MediaType HTML_UTF8 = MediaType.parseMediaType("text/html;charset=UTF-8");
    private static final MediaType TEXT_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    /** 允许分发的后缀 → Content-Type */
    private static final Map<String, MediaType> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", HTML_UTF8),
            Map.entry("css", MediaType.parseMediaType("text/css;charset=UTF-8")),
            Map.entry("js", MediaType.parseMediaType("text/javascript;charset=UTF-8")),
            Map.entry("mjs", MediaType.parseMediaType("text/javascript;charset=UTF-8")),
            Map.entry("map", MediaType.APPLICATION_JSON),
            Map.entry("json", MediaType.APPLICATION_JSON),
            Map.entry("png", MediaType.IMAGE_PNG),
            Map.entry("jpg", MediaType.IMAGE_JPEG),
            Map.entry("jpeg", MediaType.IMAGE_JPEG),
            Map.entry("gif", MediaType.IMAGE_GIF),
            Map.entry("svg", MediaType.parseMediaType("image/svg+xml")),
            Map.entry("webp", MediaType.parseMediaType("image/webp")),
            Map.entry("ico", MediaType.parseMediaType("image/x-icon")),
            Map.entry("txt", TEXT_UTF8),
            Map.entry("md", MediaType.parseMediaType("text/markdown;charset=UTF-8")),
            Map.entry("woff", MediaType.parseMediaType("font/woff")),
            Map.entry("woff2", MediaType.parseMediaType("font/woff2")),
            Map.entry("ttf", MediaType.parseMediaType("font/ttf")),
            Map.entry("otf", MediaType.parseMediaType("font/otf")),
            Map.entry("pdf", MediaType.APPLICATION_PDF),
            Map.entry("mp4", MediaType.parseMediaType("video/mp4")),
            Map.entry("webm", MediaType.parseMediaType("video/webm")),
            Map.entry("mp3", MediaType.parseMediaType("audio/mpeg")),
            Map.entry("wav", MediaType.parseMediaType("audio/wav")));

    private static final String NOT_FOUND_BODY = """
            <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">\
            <meta name="viewport" content="width=device-width,initial-scale=1">\
            <title>页面不存在</title></head>\
            <body style="margin:0;min-height:100vh;display:flex;flex-direction:column;\
            align-items:center;justify-content:center;font-family:system-ui,sans-serif;\
            background:#fafaf7;color:#3f3f46">\
            <h1 style="font-size:64px;margin:0;color:#a8a29e">404</h1>\
            <p style="margin:12px 0 0">页面不存在或尚未发布</p></body></html>""";

    private final PageMappingService pageMappingService;

    @Value("${app.data-root}")
    private String dataRoot;

    /**
     * 不带结尾斜杠的入口一律 301 补斜杠，否则页面内相对路径会解析错。
     *
     * @param pageId 页面访问 ID
     * @return 301 重定向
     */
    @GetMapping("/page/{pageId:\\d+}")
    public ResponseEntity<Void> root(@PathVariable String pageId) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/page/" + pageId + "/")
                .build();
    }

    /**
     * 分发 page/ 目录下的静态文件；以 / 结尾的路径补 index.html。
     *
     * @param pageId  页面访问 ID
     * @param request 请求
     * @return X-Accel-Redirect / 文件流 / 友好 404
     */
    @GetMapping("/page/{pageId:\\d+}/**")
    public ResponseEntity<?> serve(@PathVariable String pageId, HttpServletRequest request) {
        long id;
        try {
            id = Long.parseLong(pageId);
        } catch (NumberFormatException e) {
            // 19 位以上数字超出 long 上限：按不存在的页面处理
            return notFound();
        }
        PageMapping mapping = pageMappingService.find(id);
        if (mapping == null) {
            return notFound();
        }
        String uri = request.getRequestURI();
        String prefix = "/page/" + pageId + "/";
        if (!uri.startsWith(prefix)) {
            return root(pageId);
        }
        String sub;
        try {
            sub = UriUtils.decode(uri.substring(prefix.length()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return notFound();
        }
        Resolved resolved = resolve(mapping.getWorkId(), sub);
        if (resolved == null || !Files.isRegularFile(resolved.path())) {
            return notFound();
        }
        // AI 会反复改页面：一律回源再验证（未变走 304），避免用户看旧页
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(CONTENT_TYPES.get(resolved.ext()))
                .cacheControl(CacheControl.noCache());
        if (request.getHeader("X-Accel-Capable") != null) {
            return builder.header("X-Accel-Redirect",
                    "/_internal/works/" + mapping.getWorkId() + "/page/" + resolved.relPath()).build();
        }
        // 开发期直连网关的回退：网关直接吐流，Content-Length 由 converter 处理
        return builder.body(new FileSystemResource(resolved.path()));
    }

    /** 校验并解析子路径；任一段非法、无后缀或后缀不在白名单时返回 null */
    private Resolved resolve(String workId, String sub) {
        if (sub.isEmpty() || sub.endsWith("/")) {
            sub += "index.html";
        }
        String[] segments = sub.split("/");
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()
                    || ".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        String filename = segments[segments.length - 1];
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return null;
        }
        String ext = filename.substring(dot + 1).toLowerCase();
        if (!CONTENT_TYPES.containsKey(ext)) {
            return null;
        }
        Path root = Path.of(dataRoot, "works", workId, "page").normalize();
        Path target = root.resolve(String.join("/", segments)).normalize();
        if (!target.startsWith(root)) {
            return null;
        }
        return new Resolved(target, String.join("/", segments), ext);
    }

    private static ResponseEntity<String> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(HTML_UTF8)
                .body(NOT_FOUND_BODY);
    }

    /** @param relPath page/ 内的相对路径（正斜杠分隔，已含 index.html 补全） */
    private record Resolved(Path path, String relPath, String ext) {
    }
}
