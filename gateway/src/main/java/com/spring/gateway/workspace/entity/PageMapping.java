package com.spring.gateway.workspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * page_mappings 表的实体：页面访问 ID → work 的一对一映射。
 *
 * @author hanbing
 * @since 2026-10-16
 */
@Data
@TableName("page_mappings")
public class PageMapping {

    /** 页面访问 ID，雪花 ID，对外 URL 段。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属 work ID，每 work 至多一条。 */
    private String workId;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
