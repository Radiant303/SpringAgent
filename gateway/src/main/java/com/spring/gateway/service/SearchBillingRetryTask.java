package com.spring.gateway.service;

import com.spring.gateway.mapper.SearchBillingFailureMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 搜索计费失败自动重试任务。
 *
 * @author hanbing
 * @since 2026-10-18
 */
@Component
@RequiredArgsConstructor
public class SearchBillingRetryTask {
    private static final Logger log = LoggerFactory.getLogger(SearchBillingRetryTask.class);
    /** 单次重试租约时长（秒）。 */
    private static final int LEASE_SECONDS = 120;
    private final SearchBillingFailureMapper failures;
    private final SearchBillingRetryWorker worker;

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void retryDue() {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            failures.recoverExhausted(now);
            for (Long id : failures.findDue(now)) {
                String token = UUID.randomUUID().toString();
                LocalDateTime claimedAt = LocalDateTime.now(ZoneOffset.UTC);
                if (failures.claim(id, token, claimedAt, claimedAt.plusSeconds(LEASE_SECONDS)) != 1) {
                    continue;
                }
                try {
                    worker.execute(id, token);
                } catch (Exception exception) {
                    log.error("搜索计费重试失败，id={}", id, exception);
                    worker.failed(id, token, exception);
                }
            }
        } catch (Exception exception) {
            log.error("搜索计费重试调度失败", exception);
        }
    }
}
