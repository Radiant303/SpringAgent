package com.spring.gateway.redemption.service.impl;

import com.spring.gateway.common.RedeemFailRateLimiter;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.billing.service.PointsAccountService;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.redemption.dto.AdminRedeemCreateRequest;
import com.spring.gateway.redemption.entity.RedeemCode;
import com.spring.gateway.redemption.mapper.RedeemCodeMapper;
import com.spring.gateway.settings.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兑换码批量生成单元测试。
 *
 * @author hanbing
 * @since 2026-11-11
 */
class RedeemServiceUnitTests {

    private final RedeemCodeMapper mapper = mock(RedeemCodeMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final RedeemServiceImpl service = new RedeemServiceImpl(
            mapper, mock(UserMapper.class), mock(PointsAccountService.class),
            mock(SystemSettingService.class), idGenerator, mock(RedeemFailRateLimiter.class));

    @BeforeEach
    void setup() {
        AtomicLong ids = new AtomicLong();
        when(idGenerator.nextId()).thenAnswer(invocation -> ids.incrementAndGet());
    }

    @Test
    void generateUsesOneBatchAndUniqueCodes() {
        when(mapper.insert(anyCollection(), eq(500))).thenReturn(List.of());

        List<RedeemCode> rows = service.generate(7L, request(3));

        assertEquals(3, rows.size());
        assertEquals(3, rows.stream().map(RedeemCode::getCode).distinct().count());
        verify(mapper, times(1)).insert(anyCollection(), eq(500));
        verify(mapper, never()).insert(any(RedeemCode.class));
    }

    @Test
    void duplicateBatchIsRegeneratedAndRetried() {
        when(mapper.insert(anyCollection(), eq(500)))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenReturn(List.of());

        List<RedeemCode> rows = service.generate(7L, request(2));

        assertEquals(2, rows.size());
        verify(mapper, times(2)).insert(anyCollection(), eq(500));
    }

    @Test
    void wrappedSqlDuplicateIsAlsoRetried() {
        when(mapper.insert(anyCollection(), eq(500)))
                .thenThrow(new RuntimeException(new SQLIntegrityConstraintViolationException("duplicate")))
                .thenReturn(List.of());

        assertEquals(1, service.generate(7L, request(1)).size());
        verify(mapper, times(2)).insert(anyCollection(), eq(500));
    }

    @Test
    void nonDuplicateFailureIsNotRetried() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(mapper.insert(anyCollection(), eq(500))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.generate(7L, request(1))));
        verify(mapper, times(1)).insert(anyCollection(), eq(500));
    }

    private AdminRedeemCreateRequest request(int count) {
        return new AdminRedeemCreateRequest(count, BigDecimal.ONE, 0L, 0L, null);
    }
}
