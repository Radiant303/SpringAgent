package com.spring.gateway.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计费失败事件。
 *
 * @author hanbing
 * @since 2026-10-18
 */
@Data
@TableName("search_billing_failures")
public class SearchBillingFailure {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String dedupKey;
    private String failureType;
    private String requestId;
    private String modelId;
    private String settlementStatus;
    private String deliveryStatus;
    private LocalDateTime firstFailedAt;
    private LocalDateTime lastFailedAt;
    private LocalDateTime completedAt;
    private byte[] rawBody;
    private String headers;
    private String eventId;
    private String provider;
    private Long userId;
    private String sessionId;
    private String turnId;
    private Long searches;
    /** 成功前为参考快照。 */
    private BigDecimal unitPoints;
    private BigDecimal costPoints;
    private String status;
    private Integer retryCount;
    private Integer maxRetries;
    private LocalDateTime nextRetryAt;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String errorType;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String remark;
}
