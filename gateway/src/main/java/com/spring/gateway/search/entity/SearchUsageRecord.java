package com.spring.gateway.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 搜索用量底账。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Data
@TableName("search_usage_records")
public class SearchUsageRecord {
    @TableId(type = IdType.INPUT)
    private String eventId;
    private String source;
    private String provider;
    private Long userId;
    private String sessionId;
    private String turnId;
    private String requestId;
    private String agentName;
    private Long searches;
    private BigDecimal unitPoints;
    private BigDecimal costPoints;
    private LocalDateTime createdAt;
}
