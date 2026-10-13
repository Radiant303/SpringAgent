package com.spring.gateway.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * models 表的实体：模型资费卡。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Data
@TableName("models")
public class RateCard {

    /** 兜底资费卡的模型配置 ID。 */
    public static final String DEFAULT_MODEL_ID = "__default__";

    @TableId
    private String modelId;

    /** 无缓存输入费率（积分/百万 tokens）。 */
    private BigDecimal inputPoints;

    /** 缓存命中输入费率（积分/百万 tokens）。 */
    private BigDecimal cacheReadPoints;

    /** 缓存写入费率（积分/百万 tokens）。 */
    private BigDecimal cacheWritePoints;

    /** 输出费率（积分/百万 tokens）。 */
    private BigDecimal outputPoints;

    /** 是否启用；停用后结算按兜底卡计。 */
    private Boolean enabled;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
