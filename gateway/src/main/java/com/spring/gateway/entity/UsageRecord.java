package com.spring.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * usage_records 表的实体。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Data
@TableName("usage_records")
public class UsageRecord {

    /** 轮次状态：正常完成。 */
    public static final String STATUS_FINISHED = "finished";

    /** 轮次状态：用户取消。 */
    public static final String STATUS_CANCELLED = "cancelled";

    /** 轮次状态：运行出错。 */
    public static final String STATUS_ERROR = "error";

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 雪花 ID，或催醒轮本地生成的 wake-xxx。 */
    private String turnId;

    /** 所属会话 ID。 */
    private String sessionId;

    /** 所属用户 ID。 */
    private Long userId;

    /** 模型配置 ID。 */
    private String modelId;

    /** 本轮模型请求次数。 */
    private Integer requests;

    /** 总输入 tokens（含缓存命中与缓存写入）。 */
    private Long inputTokens;

    /** 缓存命中输入 tokens（含在 inputTokens 内）。 */
    private Long cacheReadTokens;

    /** 缓存写入 tokens（含在 inputTokens 内）。 */
    private Long cacheWriteTokens;

    /** 输出 tokens。 */
    private Long outputTokens;

    /** 轮次状态：finished / cancelled / error。 */
    private String status;

    /** 是否后台任务催醒轮（出账时可与用户轮区分）。 */
    private Boolean isWake;

    /** 记录时间（UTC）。 */
    private LocalDateTime createdAt;
}
