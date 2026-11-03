package com.spring.gateway.billing.service.impl;

import com.spring.gateway.billing.entity.PointsLedger;
import com.spring.gateway.billing.mapper.PointsLedgerMapper;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.identity.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 注册积分入账单元测试。
 *
 * @author hanbing
 * @since 2026-11-03
 */
class PointsAccountServiceUnitTests {
    private final UserMapper users = mock(UserMapper.class);
    private final PointsLedgerMapper ledger = mock(PointsLedgerMapper.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final PointsAccountServiceImpl service = new PointsAccountServiceImpl(users, ledger, ids);

    @Test
    void registrationGrantUpdatesBalanceAndWritesLedger() {
        when(users.selectBalanceForUpdate(42L)).thenReturn(BigDecimal.ZERO);
        when(ids.nextId()).thenReturn(43L);

        service.grantRegistration(42L, new BigDecimal("25.125"));

        verify(users).updateBalance(42L, new BigDecimal("25.125"));
        ArgumentCaptor<PointsLedger> saved = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledger).insert(saved.capture());
        PointsLedger row = saved.getValue();
        assertEquals(43L, row.getId());
        assertEquals(42L, row.getUserId());
        assertEquals(new BigDecimal("25.125"), row.getChangeAmount());
        assertEquals(new BigDecimal("25.125"), row.getBalanceAfter());
        assertEquals(PointsLedger.TYPE_REGISTER, row.getType());
        assertEquals("42", row.getRefId());
        assertEquals("注册初始积分", row.getReason());
    }

    @Test
    void zeroRegistrationGrantSkipsBalanceAndLedger() {
        service.grantRegistration(42L, BigDecimal.ZERO);

        verifyNoInteractions(users, ledger, ids);
    }
}
