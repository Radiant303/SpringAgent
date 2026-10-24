package com.spring.gateway.storage.dto;

import java.util.List;

/**
 * 批量上传结果。
 *
 * @author hanbing
 * @since 2026-10-15
 */
public record FileBatchUploadView(List<Item> uploaded, List<Failure> failed) {

    /**
     * 上传成功的文件。
     *
     * @param index    在请求文件列表中的下标
     * @param id       文件 ID（雪花 ID 的十进制字符串，前端 JS 的 53 位整数精度装不下 64 位雪花）
     * @param url      扁平访问 URL（{scheme}://{host}/{id}.{ext}）
     * @param size     字节数
     * @param mimeType 内容类型
     */
    public record Item(int index, String id, String url, long size, String mimeType) {
    }

    /**
     * 上传失败的文件。
     *
     * @param index   在请求文件列表中的下标
     * @param message 失败原因
     */
    public record Failure(int index, String message) {
    }
}
