package com.spring.gateway.search.infrastructure;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.spring.gateway.search.dto.SearchUsageEvent;
import com.spring.gateway.search.entity.SearchBillingFailure;
import com.spring.gateway.search.entity.SearchUsageRecord;
import com.spring.gateway.search.mapper.SearchBillingFailureMapper;
import com.spring.gateway.search.mapper.SearchUsageRecordMapper;
import com.spring.gateway.search.service.SearchBillingFailureService;
import com.spring.gateway.search.service.SearchBillingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 搜索计费租约重试事务。
 *
 * @author hanbing
 * @since 2026-10-18
 */
@Service
@RequiredArgsConstructor
public class SearchBillingRetryWorker {
    private final SearchBillingFailureMapper failures;
    private final SearchUsageRecordMapper usage;
    private final SearchBillingService billing;
    private final JsonMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Long id, String token) {
        SearchBillingFailure row = failures.lockById(id);
        if (!owns(row, token)) {
            return;
        }
        SearchUsageEvent event = json.readValue(row.getRawBody(), SearchUsageEvent.class);
        billing.chargeLocal(event);
        SearchUsageRecord charged = usage.selectById(event.eventId());
        LambdaUpdateWrapper<SearchBillingFailure> update = completion(id, token)
                .set(SearchBillingFailure::getNextRetryAt, null)
                .set(SearchBillingFailure::getCompletedAt, LocalDateTime.now(ZoneOffset.UTC))
                .set(SearchBillingFailure::getStatus, "SUCCEEDED");
        if (charged != null) {
            update.set(SearchBillingFailure::getUnitPoints, charged.getUnitPoints())
                    .set(SearchBillingFailure::getCostPoints, charged.getCostPoints());
        }
        failures.update(null, update);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long id, String token, Exception error) {
        SearchBillingFailure row = failures.lockById(id);
        if (!owns(row, token)) {
            return;
        }
        boolean retry = SearchBillingFailureService.isTransient(error) && row.getRetryCount() < row.getMaxRetries();
        LocalDateTime next = retry ? LocalDateTime.now(ZoneOffset.UTC).plusSeconds(
                row.getRetryCount() == 1 ? 5 : 30) : null;
        failures.update(null, completion(id, token)
                .set(SearchBillingFailure::getStatus, retry ? "RETRYING" : "PENDING")
                .set(SearchBillingFailure::getNextRetryAt, next)
                .set(SearchBillingFailure::getErrorType, SearchBillingFailureService.safe(error.getClass().getName(), 256))
                .set(SearchBillingFailure::getErrorMessage, SearchBillingFailureService.safe(
                        error.getMessage() == null ? error.toString() : error.getMessage(), Integer.MAX_VALUE)));
    }

    private static boolean owns(SearchBillingFailure row, String token) {
        return row != null && "SEARCH_BILLING".equals(row.getFailureType())
                && "RETRYING".equals(row.getStatus()) && Objects.equals(token, row.getLeaseToken());
    }

    private static LambdaUpdateWrapper<SearchBillingFailure> completion(Long id, String token) {
        return new LambdaUpdateWrapper<SearchBillingFailure>()
                .eq(SearchBillingFailure::getId, id).eq(SearchBillingFailure::getLeaseToken, token)
                .set(SearchBillingFailure::getLeaseToken, null)
                .set(SearchBillingFailure::getLeaseUntil, null)
                .set(SearchBillingFailure::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC));
    }
}
