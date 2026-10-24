package com.spring.gateway.storage.controller;

import com.spring.gateway.storage.service.FileAccessService;
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

/**
 * 扁平文件访问接口。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@RestController
@RequiredArgsConstructor
public class FileAccessController {
    private final FileAccessService fileAccessService;

    /**
     * 访问文件。
     *
     * @param fileId 文件 ID
     * @param request 请求
     * @return 重定向或文件内容
     */
    @GetMapping("/{fileId:\\d+}.{ext:[A-Za-z0-9]{1,10}}")
    public ResponseEntity<?> access(@PathVariable String fileId, HttpServletRequest request) {
        FileAccessService.Access access = fileAccessService.open(
                fileId, request.getHeader("X-Accel-Capable") != null);
        return switch (access.kind()) {
            case DIRECT -> ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, access.location())
                    .build();
            case ACCEL -> ResponseEntity.ok()
                    .header("X-Accel-Redirect", access.location())
                    .contentType(mediaType(access.mimeType()))
                    .build();
            case LOCAL -> {
                Resource resource = new FileSystemResource(access.path());
                yield ResponseEntity.ok()
                        .contentType(mediaType(access.mimeType()))
                        .contentLength(access.sizeBytes())
                        .body(resource);
            }
        };
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
