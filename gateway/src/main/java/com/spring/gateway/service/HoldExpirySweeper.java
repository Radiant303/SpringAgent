package com.spring.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.entity.PointsHold;
import com.spring.gateway.mapper.PointsHoldMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 请求冻结单超时扫描。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpirySweeper {

    /** 请求冻结超过该时长标记为待核对。 */
    private static final long EXPIRE_MINUTES = 10;

    /** 单轮扫描处理上限，防积压长尾。 */
    private static final int BATCH_LIMIT = 100;

    private final PointsHoldMapper pointsHoldMapper;
    private final BillingService billingService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweep() {
        List<PointsHold> stale = pointsHoldMapper.selectList(new LambdaQueryWrapper<PointsHold>()
                .eq(PointsHold::getStatus, PointsHold.STATUS_HELD)
                .lt(PointsHold::getCreatedAt,
                        LocalDateTime.now(ZoneOffset.UTC).minusMinutes(EXPIRE_MINUTES))
                .last("LIMIT " + BATCH_LIMIT));
        for (PointsHold hold : stale) {
            try {
                billingService.markStale(hold.getRequestId());
                log.warn("模型请求结算超时，冻结保留待核对: requestId={} userId={} amount={}",
                        hold.getRequestId(), hold.getUserId(), hold.getAmount());
            } catch (Exception e) {
                log.error("请求超时标记失败: requestId={} error={}", hold.getRequestId(), e.toString());
            }
        }
    }
}
