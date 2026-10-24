package com.spring.gateway.redemption.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * redeem_codes 表的实体。
 *
 * @author hanbing
 * @since 2026-10-06
 */
@Data
@TableName("redeem_codes")
public class RedeemCode {

    /** 状态：待使用。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已兑换。 */
    public static final String STATUS_REDEEMED = "REDEEMED";

    /** 状态：已作废（站长风控出口）。 */
    public static final String STATUS_REVOKED = "REVOKED";

    /** 主键，雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 兑换码（规范形：大写、无连字符），唯一。 */
    private String code;

    /** 状态：ACTIVE / REDEEMED / REVOKED。 */
    private String status;

    /** 面值：积分。 */
    private BigDecimal pointsAmount;

    /** 面值：存储配额增量（字节）。 */
    private Long storageDeltaBytes;

    /** 面值：单工作区上限增量（字节）。 */
    private Long workQuotaDeltaBytes;

    /** 失效时间（UTC）；null = 永久有效。 */
    private LocalDateTime expiresAt;

    /** 生成人（站长）用户 ID。 */
    private Long createdBy;

    /** 兑换人用户 ID。 */
    private Long redeemedBy;

    /** 兑换时间（UTC）。 */
    private LocalDateTime redeemedAt;

    /** 软删除时间（兑换成功时置位）。 */
    private LocalDateTime deletedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
