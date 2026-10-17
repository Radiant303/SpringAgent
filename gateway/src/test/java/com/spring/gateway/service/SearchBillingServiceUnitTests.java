package com.spring.gateway.service;

import com.spring.gateway.common.BizException;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.dto.SearchUsageEvent;
import com.spring.gateway.entity.PointsHold;
import com.spring.gateway.entity.PointsLedger;
import com.spring.gateway.entity.SearchUsageRecord;
import com.spring.gateway.entity.Session;
import com.spring.gateway.mapper.PointsLedgerMapper;
import com.spring.gateway.mapper.SearchUsageRecordMapper;
import com.spring.gateway.mapper.SessionMapper;
import com.spring.gateway.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 搜索次数计费单元测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class SearchBillingServiceUnitTests {
    private final UserMapper users = mock(UserMapper.class);
    private final SessionMapper sessions = mock(SessionMapper.class);
    private final SearchUsageRecordMapper records = mock(SearchUsageRecordMapper.class);
    private final PointsLedgerMapper ledger = mock(PointsLedgerMapper.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final AtomicReference<BigDecimal> balance = new AtomicReference<>(BigDecimal.TEN);
    private final Map<String, SearchUsageRecord> stored = new HashMap<>();
    private SearchBillingService service;
    private final String sessionId = UUID.randomUUID().toString();

    @BeforeEach
    void fixture() {
        service = new SearchBillingService(users, sessions, records, ledger, settings, ids);
        when(users.selectBalanceForUpdate(7L)).thenAnswer(call -> balance.get());
        when(users.updateBalance(eq(7L), any(BigDecimal.class))).thenAnswer(call -> {
            balance.set(call.getArgument(1));
            return 1;
        });
        when(records.selectById(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(records.insert(any(SearchUsageRecord.class))).thenAnswer(call -> {
            SearchUsageRecord row = call.getArgument(0);
            stored.put(row.getEventId(), row);
            return 1;
        });
        when(settings.getSearchPoints(anyString())).thenReturn(BigDecimal.ONE);
        when(ids.nextId()).thenReturn(42L);
    }

    private SearchUsageEvent event(String id, String provider) {
        return new SearchUsageEvent(id, "local", provider, 1L, 7L, sessionId, "turn", null, null);
    }

    private PointsHold hold() {
        PointsHold hold = new PointsHold();
        hold.setRequestId("0123456789abcdef0123456789abcdef");
        hold.setUserId(7L);
        hold.setSessionId(sessionId);
        hold.setTurnId("turn");
        hold.setAgentName("main");
        return hold;
    }

    private void amount(String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(balance.get()));
    }

    @Test
    void snapshotsProviderPriceAndNeverRepricesReplay() {
        when(settings.getSearchPoints("tavily")).thenReturn(new BigDecimal("1.123456"));
        SearchUsageEvent event = event("event-1", "tavily");
        service.chargeLocal(event);
        when(settings.getSearchPoints("tavily")).thenReturn(new BigDecimal("9"));
        service.chargeLocal(event);
        amount("8.876544");
        assertEquals(1, stored.size());
        assertEquals(new BigDecimal("1.123456"), stored.get("event-1").getUnitPoints());
        verify(settings, times(1)).getSearchPoints("tavily");
        ArgumentCaptor<PointsLedger> capture = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledger).insert(capture.capture());
        assertEquals(PointsLedger.TYPE_SEARCH, capture.getValue().getType());
        assertEquals("event-1", capture.getValue().getRefId());
        assertEquals("搜索 tavily × 1", capture.getValue().getReason());
    }

    @Test
    void chargesEachSuccessfulEventNotEachTurnOrAgent() {
        when(settings.getSearchPoints("exa")).thenReturn(new BigDecimal("0.5"));
        service.chargeLocal(event("first", "tavily"));
        service.chargeLocal(event("second", "exa"));
        service.chargeLocal(new SearchUsageEvent("child", "local", "exa", 1L, 7L,
                sessionId, "turn", "child-request", "reviewer"));
        amount("8");
        assertEquals(3, stored.size());
    }

    @Test
    void rejectsReplayWithChangedOwnershipOrCount() {
        SearchUsageEvent original = event("event-1", "tavily");
        service.chargeLocal(original);
        for (SearchUsageEvent changed : new SearchUsageEvent[]{
                new SearchUsageEvent("event-1", "local", "exa", 1L, 7L, sessionId, "turn", null, null),
                new SearchUsageEvent("event-1", "local", "tavily", 2L, 7L, sessionId, "turn", null, null),
                new SearchUsageEvent("event-1", "local", "tavily", 1L, 7L, sessionId, "other", null, null),
                new SearchUsageEvent("event-1", "local", "tavily", 1L, 7L, sessionId, "turn", "request", null),
                new SearchUsageEvent("event-1", "local", "tavily", 1L, 7L, sessionId, "turn", null, "child"),
                new SearchUsageEvent("event-1", "local", "tavily", 1L, 7L,
                        UUID.randomUUID().toString(), "turn", null, null)}) {
            assertEquals(409, assertThrows(BizException.class, () -> service.chargeLocal(changed)).getStatus());
        }
        when(users.selectBalanceForUpdate(8L)).thenReturn(BigDecimal.TEN);
        SearchUsageEvent otherUser = new SearchUsageEvent("event-1", "local", "tavily", 1L, 8L,
                sessionId, "turn", null, null);
        assertEquals(409, assertThrows(BizException.class, () -> service.chargeLocal(otherUser)).getStatus());
        amount("9");
    }

    @Test
    void permitsLateEventsAfterSessionDeletionAndNegativeBalance() {
        balance.set(new BigDecimal("0.25"));
        service.chargeLocal(event("late", "tavily"));
        amount("-0.75");
        assertNotNull(stored.get("late"));
        verify(sessions).selectById(sessionId);
    }

    @Test
    void requiresExistingUserAndRejectsAnotherUsersSession() {
        when(users.selectBalanceForUpdate(7L)).thenReturn(null);
        assertEquals(404, assertThrows(BizException.class,
                () -> service.chargeLocal(event("missing-user", "tavily"))).getStatus());
        when(users.selectBalanceForUpdate(7L)).thenAnswer(call -> balance.get());
        Session session = new Session();
        session.setUserId(8L);
        when(sessions.selectById(sessionId)).thenReturn(session);
        assertEquals(409, assertThrows(BizException.class,
                () -> service.chargeLocal(event("wrong-session", "tavily"))).getStatus());
        assertTrue(stored.isEmpty());
        verifyNoInteractions(ledger);
    }

    @Test
    void persistsFreeSearchesWithoutLedgerAndRetainsIdempotency() {
        when(settings.getSearchPoints("exa")).thenReturn(BigDecimal.ZERO);
        SearchUsageEvent event = event("free", "exa");
        service.chargeLocal(event);
        when(settings.getSearchPoints("exa")).thenReturn(BigDecimal.TEN);
        service.chargeLocal(event);
        amount("10");
        assertEquals(0, stored.get("free").getCostPoints().signum());
        verifyNoInteractions(ledger);
        verify(users, never()).updateBalance(anyLong(), any());
    }

    @Test
    void unknownNativeUsageCanBeReportedLaterAndZeroIsFinal() {
        PointsHold hold = hold();
        service.chargeNative(hold, null);
        assertTrue(stored.isEmpty());
        service.chargeNative(hold, 0L);
        service.chargeNative(hold, 0L);
        service.chargeNative(hold, null);
        SearchUsageRecord record = stored.get("native:" + hold.getRequestId());
        assertEquals(0L, record.getSearches());
        assertEquals("native", record.getProvider());
        assertEquals("native", record.getSource());
        assertEquals(409, assertThrows(BizException.class, () -> service.chargeNative(hold, 1L)).getStatus());
        amount("10");
        verifyNoInteractions(ledger);
    }

    @Test
    void nativeCountUsesExactMultiplicationAndIndependentProviderPrice() {
        when(settings.getSearchPoints("native")).thenReturn(new BigDecimal("1.234567"));
        service.chargeNative(hold(), 3L);
        service.chargeNative(hold(), 3L);
        amount("6.296299");
        SearchUsageRecord record = stored.get("native:" + hold().getRequestId());
        assertEquals(new BigDecimal("3.703701"), record.getCostPoints());
        verify(settings, times(1)).getSearchPoints("native");
    }

    @Test
    void duplicateInsertUsesCommittedRecordWithoutDoubleDebit() {
        SearchUsageEvent event = event("racing", "tavily");
        service.chargeLocal(event);
        SearchUsageRecord committed = stored.remove("racing");
        doThrow(new DuplicateKeyException("duplicate")).when(records).insert(any(SearchUsageRecord.class));
        when(records.selectForUpdate("racing")).thenReturn(committed);
        service.chargeLocal(event);
        amount("9");
        verify(ledger, times(1)).insert(any(PointsLedger.class));
        committed.setUserId(8L);
        assertEquals(409, assertThrows(BizException.class, () -> service.chargeLocal(event)).getStatus());
    }

    @Test
    void rejectsMalformedLocalEventsWithoutWriting() {
        for (SearchUsageEvent event : new SearchUsageEvent[]{
                new SearchUsageEvent("bad", "native", "native", 1L, 7L, sessionId, "turn", null, null),
                new SearchUsageEvent("bad", "local", "unknown", 1L, 7L, sessionId, "turn", null, null),
                new SearchUsageEvent("bad", "local", null, 1L, 7L, sessionId, "turn", null, null),
                new SearchUsageEvent("bad", "local", "exa", 0L, 7L, sessionId, "turn", null, null),
                new SearchUsageEvent("bad", "local", "exa", -1L, 7L, sessionId, "turn", null, null),
                new SearchUsageEvent("bad", "local", "exa", null, 7L, sessionId, "turn", null, null),
                new SearchUsageEvent("x".repeat(65), "local", "exa", 1L, 7L, sessionId, "turn", null, null),
                new SearchUsageEvent("bad", "local", "exa", 1L, 7L, "x".repeat(37), "turn", null, null),
                new SearchUsageEvent("bad", "local", "exa", 1L, 7L, sessionId, " ", null, null)}) {
            assertEquals(400, assertThrows(BizException.class, () -> service.chargeLocal(event)).getStatus());
        }
        assertTrue(stored.isEmpty());
        verifyNoInteractions(ledger);
    }

    @Test
    void rejectsNegativeNativeCountAndUnrepresentableCost() {
        assertEquals(400, assertThrows(BizException.class, () -> service.chargeNative(hold(), -1L)).getStatus());
        when(settings.getSearchPoints("native")).thenReturn(new BigDecimal("0.1234567"));
        assertEquals(400, assertThrows(BizException.class, () -> service.chargeNative(hold(), 1L)).getStatus());
        when(settings.getSearchPoints("native")).thenReturn(BigDecimal.ONE);
        assertEquals(400, assertThrows(BizException.class,
                () -> service.chargeNative(hold(), Long.MAX_VALUE)).getStatus());
        assertTrue(stored.isEmpty());
    }
}
