package com.spring.gateway.workspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话回答变体节点。
 *
 * @author hanbing
 * @since 2026-11-27
 */
@Data
@TableName("message_nodes")
public class MessageNode {
    public static final String KIND_NORMAL = "normal";
    public static final String KIND_EDIT = "edit";
    public static final String KIND_RETRY = "retry";
    public static final String KIND_LEGACY = "legacy";

    /** 节点 ID，UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属会话 ID。 */
    private String sessionId;

    /** 父节点 ID；根节点为 null。 */
    private String parentId;

    /** 同一问题的版本组 ID。 */
    private String groupId;

    /** 节点类型：normal / edit / retry / legacy。 */
    private String kind;

    /** 组内版本号，从 1 开始。 */
    private Integer version;

    /** 节点所在历史段号。 */
    private Integer segmentNo;

    /** 旧消息前缀的截止主键，包含该行。 */
    private Long legacyAnchorId;

    /** 旧节点的结束主键，包含该行。 */
    private Long legacyEndId;

    /** 旧问题首条消息的主键。 */
    private Long legacyTargetId;

    /** 创建时间。 */
    private LocalDateTime createdAt;
}
