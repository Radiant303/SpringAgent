package com.spring.gateway.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * points_holds 表的实体。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Data
@TableName("points_holds")
public class PointsHold {

    /** 状态：在途预扣。 */
    public static final String STATUS_HELD = "HELD";

    /** 状态：已结算。 */
    public static final String STATUS_SETTLED = "SETTLED";

    /** 状态：已释放（全额退回）。 */
    public static final String STATUS_RELEASED = "RELEASED";

    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String STATUS_STALE = "STALE";

    @TableId(type = IdType.INPUT)
    private String requestId;

    private String turnId;
    private String sessionId;
    private String modelId;
    private String agentName;
    private BigDecimal inputPoints;
    private BigDecimal cacheReadPoints;
    private BigDecimal cacheWritePoints;
    private BigDecimal outputPoints;

    /** 所属用户 ID。 */
    private Long userId;

    /** 预扣额（积分，正数）。 */
    private BigDecimal amount;

    /** HELD / SETTLED / UNKNOWN / STALE / RELEASED。 */
    private String status;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 收口时间（UTC）；在途为 null。 */
    private LocalDateTime settledAt;
}
