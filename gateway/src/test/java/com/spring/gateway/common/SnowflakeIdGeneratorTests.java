package com.spring.gateway.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花 ID 生成器单元测试。不依赖 Spring 与 Redis，直接实例化生成器测试。
 *
 * @author hanbing
 * @since 2026-09-25
 */
class SnowflakeIdGeneratorTests {

    @Test
    @DisplayName("同一实例生成的 ID 严格单调递增")
    void monotonic() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1);
        long prev = -1L;
        for (int i = 0; i < 10_000; i++) {
            long id = gen.nextId();
            assertTrue(id > prev, "ID 必须严格递增");
            prev = id;
        }
    }

    @Test
    @DisplayName("16 线程并发生成 10 万个 ID 无重复")
    void concurrentNoDuplicate() throws InterruptedException {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1);
        int threads = 16;
        int perThread = 6250;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    // 所有线程阻塞在起点，统一释放以最大化并发竞争
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(gen.nextId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发生成超时");
        assertEquals(threads * perThread, ids.size(), "出现重复 ID");
    }

    @Test
    @DisplayName("workerId 被正确编码进 ID，可以反解出来")
    void workerBitsRecoverable() {
        long workerId = 42;
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(workerId);
        long id = gen.nextId();

        long decodedWorker = (id >> SnowflakeIdGenerator.WORKER_ID_SHIFT)
                & SnowflakeIdGenerator.MAX_WORKER_ID;
        assertEquals(workerId, decodedWorker);

        // 时间戳位应可反解回真实毫秒时间
        long decodedTs = (id >> SnowflakeIdGenerator.TIMESTAMP_SHIFT) + SnowflakeIdGenerator.EPOCH;
        assertTrue(Math.abs(decodedTs - System.currentTimeMillis()) < 1000);
    }

    @Test
    @DisplayName("时钟回拨超过 5ms：拒绝发号")
    void rollbackBeyondToleranceThrows() {
        long now = System.currentTimeMillis();
        LongSupplier clock = fakeClock(now, now - 100);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, clock);

        gen.nextId();
        assertThrows(IllegalStateException.class, gen::nextId);
    }

    @Test
    @DisplayName("时钟回拨 5ms 以内：等待时钟追平后继续发号，ID 仍然递增")
    void smallRollbackWaits() {
        long now = System.currentTimeMillis();
        // 第 3 次读取时钟时回拨 2ms，随后逐步追平
        LongSupplier clock = fakeClock(now, now, now - 2, now - 1, now, now + 1);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, clock);

        long id1 = gen.nextId();
        long id2 = gen.nextId();
        long id3 = gen.nextId();
        long id4 = gen.nextId();

        assertTrue(id1 < id2 && id2 < id3 && id3 < id4, "回拨场景下 ID 仍须严格递增");
    }

    @Test
    @DisplayName("workerId 超出 0~1023 范围直接拒绝")
    void workerIdRangeChecked() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
    }

    /**
     * 构造假时钟：按给定序列依次返回时间戳，序列读完后停留在最后一个值
     *
     * @param values 时间戳序列
     * @return 假时钟
     */
    private static LongSupplier fakeClock(long... values) {
        AtomicInteger idx = new AtomicInteger();
        return () -> values[Math.min(idx.getAndIncrement(), values.length - 1)];
    }
}
