package com.spring.gateway.dto;

import com.spring.gateway.common.TimeFormat;
import com.spring.gateway.entity.Work;

/**
 * 项目与用户文件资源的内部摘要。
 *
 * @author hanbing
 * @since 2026-10-04
 * @param workId        work ID
 * @param userId        所属用户 ID
 * @param name          项目名
 * @param sizeBytes     目录当前占用字节数
 * @param defaultWork   是否默认项目
 * @param workspacePath 项目文件工作区目录
 * @param knowledgePath 用户知识工作区目录
 * @param workMaxBytes  生效的单工作区上限字节数
 * @param pageId        页面访问 ID（雪花 ID 的字符串形），无映射为 null
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record InternalWorkSummary(
        String workId,
        Long userId,
        String name,
        long sizeBytes,
        boolean defaultWork,
        String workspacePath,
        String knowledgePath,
        long workMaxBytes,
        String pageId,
        String createdAt,
        String updatedAt
) {

    public static InternalWorkSummary from(Work row, String workspacePath, String knowledgePath,
                                           long workMaxBytes, Long pageId) {
        return new InternalWorkSummary(
                row.getId(),
                row.getUserId(),
                row.getName(),
                row.getSizeBytes() == null ? 0L : row.getSizeBytes(),
                Boolean.TRUE.equals(row.getIsDefault()),
                workspacePath,
                knowledgePath,
                workMaxBytes,
                pageId == null ? null : String.valueOf(pageId),
                TimeFormat.isoUtc(row.getCreatedAt()),
                TimeFormat.isoUtc(row.getUpdatedAt()));
    }
}
