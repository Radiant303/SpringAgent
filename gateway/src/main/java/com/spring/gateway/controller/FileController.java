package com.spring.gateway.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.Result;
import com.spring.gateway.dto.FileUploadView;
import com.spring.gateway.entity.FileRecord;
import com.spring.gateway.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件上传/删除接口（登录用户）。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /**
     * 上传文件到指定会话，返回扁平访问 URL
     *
     * @param userId    当前用户 ID（拦截器注入）
     * @param sessionId 目标会话 ID
     * @param file      文件
     * @param request   请求（拼 URL 的 scheme/host 用）
     * @return 文件视图
     */
    @PostMapping
    public Result<FileUploadView> upload(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                         @RequestParam("sessionId") String sessionId,
                                         @RequestParam("file") MultipartFile file,
                                         HttpServletRequest request) throws IOException {
        FileRecord row = fileStorageService.upload(userId, sessionId, file.getOriginalFilename(),
                file.getContentType(), file.getSize(), file.getInputStream());
        return Result.ok(new FileUploadView(row.getUuid(), fileUrl(request, row),
                row.getSizeBytes(), row.getMimeType()));
    }

    /**
     * 删除自己上传的文件
     *
     * @param userId 当前用户 ID（拦截器注入）
     * @param uuid   文件 UUID
     * @return 空数据返回体
     */
    @DeleteMapping("/{uuid:[0-9a-f]{32}}")
    public Result<Void> delete(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                               @PathVariable String uuid) {
        fileStorageService.delete(userId, uuid);
        return Result.ok(null);
    }

    /** 扁平 URL 现拼：不入库；经 Nginx 访问时 scheme 取 X-Forwarded-Proto */
    private static String fileUrl(HttpServletRequest request, FileRecord row) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }
        String ext = row.getPath().substring(row.getPath().lastIndexOf('.') + 1);
        return scheme + "://" + request.getHeader("Host") + "/" + row.getUuid() + "." + ext;
    }
}
