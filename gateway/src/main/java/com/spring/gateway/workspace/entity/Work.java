package com.spring.gateway.workspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * works 表的实体。
 *
 * @author hanbing
 * @since 2026-10-04
 */
@Data
@TableName("works")
public class Work {

    /** work ID，UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户 ID。 */
    private Long userId;

    /** 项目名。 */
    private String name;

    /** 项目文件及远程上传的占用字节数。 */
    private Long sizeBytes;

    private Long internalBytes;

    /** 是否该用户的默认项目，每用户至多一个。 */
    private Boolean isDefault;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
