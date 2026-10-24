package com.spring.gateway.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.search.dto.SearchUsageEvent;
import com.spring.gateway.billing.entity.PointsLedger;
import com.spring.gateway.search.entity.SearchUsageRecord;
import com.spring.gateway.identity.entity.User;
import com.spring.gateway.billing.mapper.PointsLedgerMapper;
import com.spring.gateway.search.mapper.SearchUsageRecordMapper;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.search.service.SearchBillingService;
import com.spring.gateway.settings.service.SystemSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 搜索计费并发事务测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@SpringBootTest
class SearchBillingConcurrencyTests {
    @Autowired private SearchBillingService billing;
    @Autowired private SearchUsageRecordMapper records;
    @Autowired private PointsLedgerMapper ledger;
    @Autowired private UserMapper users;
    @Autowired private SnowflakeIdGenerator ids;
    @Autowired private SystemSettingService settings;
    private long userId;
    private String sessionId;
    private String eventPrefix;

    @BeforeEach
    void fixture() {
        User user = new User();
        user.setId(ids.nextId());
        user.setUsername("s_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setStatus(User.STATUS_ACTIVE);
        user.setPointsBalance(BigDecimal.TEN);
        users.insert(user);
        userId = user.getId();
        sessionId = UUID.randomUUID().toString();
        eventPrefix = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanup() {
        records.delete(new LambdaQueryWrapper<SearchUsageRecord>().eq(SearchUsageRecord::getUserId, userId));
        ledger.delete(new LambdaQueryWrapper<PointsLedger>().eq(PointsLedger::getUserId, userId));
        users.deleteById(userId);
    }

    private SearchUsageEvent event(String id) {
        return new SearchUsageEvent(id, "local", "tavily", 1L, userId, sessionId, "same-turn", null, "main");
    }

    private void race(IntFunction<SearchUsageEvent> events) throws Exception {
        int count = 8;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                SearchUsageEvent event = events.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发计费启动超时");
                    }
                    billing.chargeLocal(event);
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private void assertCharges(int count) {
        BigDecimal price = settings.getSearchPoints("tavily");
        BigDecimal expected = BigDecimal.TEN.subtract(price.multiply(BigDecimal.valueOf(count)));
        assertEquals(0, expected.compareTo(users.selectById(userId).getPointsBalance()));
        assertEquals(count, records.selectCount(new LambdaQueryWrapper<SearchUsageRecord>()
                .eq(SearchUsageRecord::getUserId, userId)));
        assertEquals(price.signum() == 0 ? 0 : count, ledger.selectCount(new LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getUserId, userId).eq(PointsLedger::getType, PointsLedger.TYPE_SEARCH)));
    }

    @Test
    void concurrentReplayDebitsExactlyOnce() throws Exception {
        race(index -> event(eventPrefix));
        assertCharges(1);
    }

    @Test
    void concurrentDistinctEventsNeverOverwriteBalance() throws Exception {
        race(index -> event(eventPrefix + ":" + index));
        assertCharges(8);
    }
}
