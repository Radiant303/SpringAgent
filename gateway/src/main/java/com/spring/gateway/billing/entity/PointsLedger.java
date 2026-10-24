package com.spring.gateway.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * points_ledger 表的实体。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Data
@TableName("points_ledger")
public class PointsLedger {

    /** 类型：模型请求冻结。 */
    public static final String TYPE_HOLD = "HOLD";

    /** 类型：结算净差（多退少补，可正可负）。 */
    public static final String TYPE_SETTLE = "SETTLE";

    /** 类型：预扣释放退回。 */
    public static final String TYPE_RELEASE = "RELEASE";

    /** 历史直接扣费流水类型。 */
    public static final String TYPE_DIRECT = "DIRECT";

    /** 类型：站长调账。 */
    public static final String TYPE_ADJUST = "ADJUST";

    /** 类型：兑换码入账（ref_id = 码 ID）。 */
    public static final String TYPE_REDEEM = "REDEEM";

    public static final String TYPE_SEARCH = "SEARCH";

    /** 主键，雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属用户 ID。 */
    private Long userId;

    /** 变动额（积分，有符号；负 = 扣）。 */
    private BigDecimal changeAmount;

    /** 变动后余额（审计快照）。 */
    private BigDecimal balanceAfter;

    private String type;

    /** 计费请求单号、搜索事件 ID、调账单号或兑换码 ID。 */
    private String refId;

    /** 结算时实际计费的模型配置 ID。 */
    private String modelId;

    /** ADJUST 的操作人用户 ID。 */
    private Long operatorId;

    /** 调账事由或搜索厂商及次数。 */
    private String reason;

    /** 记录时间（UTC）。 */
    private LocalDateTime createdAt;
}
