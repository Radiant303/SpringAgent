package com.spring.gateway.dto;

/**
 * 上传成功的文件视图。
 *
 * @param uuid     文件 UUID
 * @param url      扁平访问 URL（{scheme}://{host}/{uuid}.{ext}）
 * @param size     字节数
 * @param mimeType 内容类型
 */
public record FileUploadView(String uuid, String url, long size, String mimeType) {
}
