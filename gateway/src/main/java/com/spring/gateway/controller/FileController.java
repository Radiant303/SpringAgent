package com.spring.gateway.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.BizException;
import com.spring.gateway.common.Result;
import com.spring.gateway.dto.FileBatchUploadView;
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
import java.util.ArrayList;
import java.util.List;

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

    /** 单次批量上传文件数上限。 */
    public static final int MAX_BATCH_FILES = 50;

    /**
     * 上传文件到指定会话，返回扁平访问 URL
     *
     * @param userId    当前用户 ID
     * @param sessionId 目标会话 ID
     * @param file      文件
     * @param request   请求
     * @return 文件视图
     */
    @PostMapping
    public Result<FileUploadView> upload(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                         @RequestParam("sessionId") String sessionId,
                                         @RequestParam("file") MultipartFile file,
                                         HttpServletRequest request) throws IOException {
        FileRecord row = fileStorageService.upload(userId, sessionId, file.getOriginalFilename(),
                file.getContentType(), file.getSize(), file.getInputStream());
        return Result.ok(new FileUploadView(String.valueOf(row.getId()), fileUrl(request, row),
                row.getSizeBytes(), row.getMimeType()));
    }

    /**
     * 批量上传文件到指定会话；逐文件独立成败，互不影响
     *
     * @param userId    当前用户 ID
     * @param sessionId 目标会话 ID
     * @param files     文件列表
     * @param request   请求
     * @return 每个文件的上传结果
     * @throws BizException 文件数量为空或超限（400）
     */
    @PostMapping("/batch")
    public Result<FileBatchUploadView> uploadBatch(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                                   @RequestParam("sessionId") String sessionId,
                                                   @RequestParam("files") MultipartFile[] files,
                                                   HttpServletRequest request) {
        if (files == null || files.length == 0) {
            throw new BizException(400, "未选择文件");
        }
        if (files.length > MAX_BATCH_FILES) {
            throw new BizException(400, "一次最多上传 " + MAX_BATCH_FILES + " 个文件");
        }
        List<FileBatchUploadView.Item> uploaded = new ArrayList<>();
        List<FileBatchUploadView.Failure> failed = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            try {
                FileRecord row = fileStorageService.upload(userId, sessionId, file.getOriginalFilename(),
                        file.getContentType(), file.getSize(), file.getInputStream());
                uploaded.add(new FileBatchUploadView.Item(i, String.valueOf(row.getId()), fileUrl(request, row),
                        row.getSizeBytes(), row.getMimeType()));
            } catch (BizException | IOException e) {
                failed.add(new FileBatchUploadView.Failure(i, e.getMessage()));
            }
        }
        return Result.ok(new FileBatchUploadView(uploaded, failed));
    }

    /**
     * 删除自己上传的文件
     *
     * @param userId 当前用户 ID
     * @param fileId 文件 ID
     * @return 空数据返回体
     */
    @DeleteMapping("/{fileId:\\d{1,19}}")
    public Result<Void> delete(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                               @PathVariable long fileId) {
        fileStorageService.delete(userId, fileId);
        return Result.ok(null);
    }

    /** 扁平 URL 现拼，不入库。 */
    private static String fileUrl(HttpServletRequest request, FileRecord row) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }
        String ext = row.getPath().substring(row.getPath().lastIndexOf('.') + 1);
        return scheme + "://" + request.getHeader("Host") + "/" + row.getId() + "." + ext;
    }
}
