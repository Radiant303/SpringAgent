package com.spring.gateway.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * files 表的实体：用户上传的文件。
 *
 * @author hanbing
 * @since 2026-10-14
 */
@Data
@TableName("files")
public class FileRecord {

    /** 记录 ID，雪花 ID，同时是文件名（扁平 URL 与存储 key 共用）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 上传者用户 ID。 */
    private Long userId;

    /** 上传时所属会话 ID。 */
    private String sessionId;

    /** 存储类型（local / aliyun_oss）。 */
    private String storageType;

    /** 存储内地址：本地 = 相对 data-root 的 key；OSS = object key。 */
    private String path;

    /** 文件字节数（列名 size）。 */
    @TableField("size")
    private Long sizeBytes;

    /** 内容类型。 */
    private String mimeType;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
