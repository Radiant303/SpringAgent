package com.spring.gateway.storage.dto;

/**
 * 上传成功的文件视图。
 *
 * @param id       文件 ID（雪花 ID 的十进制字符串，前端 JS 的 53 位整数精度装不下 64 位雪花）
 * @param url      扁平访问 URL（{scheme}://{host}/{id}.{ext}）
 * @param size     字节数
 * @param mimeType 内容类型
 */
public record FileUploadView(String id, String url, long size, String mimeType) {
}
