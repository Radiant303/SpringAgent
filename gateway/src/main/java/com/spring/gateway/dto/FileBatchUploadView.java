package com.spring.gateway.dto;

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
     * @param uuid     文件 UUID
     * @param url      扁平访问 URL（{scheme}://{host}/{uuid}.{ext}）
     * @param size     字节数
     * @param mimeType 内容类型
     */
    public record Item(int index, String uuid, String url, long size, String mimeType) {
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
