package com.spring.gateway.controller;

import com.spring.gateway.common.BizException;
import com.spring.gateway.entity.FileRecord;
import com.spring.gateway.service.FileStorageService;
import com.spring.gateway.service.storage.ObjectStorageService;
import com.spring.gateway.service.storage.StorageServiceSelector;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 扁平文件访问：GET /{id}.{ext}，无需登录。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@RestController
@RequiredArgsConstructor
public class FileAccessController {

    private final FileStorageService fileStorageService;
    private final StorageServiceSelector storageSelector;

    /**
     * 访问文件
     *
     * @param fileId  文件 ID
     * @param request 请求
     * @return 302 / X-Accel-Redirect / 文件流
     */
    @GetMapping("/{fileId:\\d{1,19}}.{ext:[A-Za-z0-9]{1,10}}")
    public ResponseEntity<?> access(@PathVariable long fileId, HttpServletRequest request) {
        FileRecord row = fileStorageService.requireById(fileId);
        ObjectStorageService storage = storageSelector.get(row.getStorageType());
        Optional<String> direct = storage.directUrl(row.getPath());
        if (direct.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, direct.get())
                    .build();
        }
        Path path = storage.localPath(row.getPath())
                .orElseThrow(() -> new BizException(502, "该存储类型不支持本地分发"));
        if (!Files.isRegularFile(path)) {
            throw new BizException(404, "文件不存在");
        }
        if (request.getHeader("X-Accel-Capable") != null) {
            // 经 Nginx：指路给 /_internal/，由 Nginx 读磁盘发字节
            return ResponseEntity.ok()
                    .header("X-Accel-Redirect", "/_internal/" + row.getPath())
                    .contentType(mediaType(row.getMimeType()))
                    .build();
        }
        // 开发期直连网关的回退：网关直接吐流
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(mediaType(row.getMimeType()))
                .contentLength(row.getSizeBytes())
                .body(resource);
    }

    private static MediaType mediaType(String mimeType) {
        try {
            return mimeType == null || mimeType.isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
