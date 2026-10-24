package com.spring.gateway.storage.infrastructure;

import com.spring.gateway.settings.dto.AdminStorageSettingsRequest;
import com.spring.gateway.settings.entity.SystemSetting;
import com.spring.gateway.identity.mapper.UserMapper;
import com.spring.gateway.identity.service.MailCodeService;
import com.spring.gateway.identity.service.impl.UserProfileServiceImpl;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.settings.service.SystemSettingWriter;
import com.spring.gateway.settings.service.SystemSettingAdminService;
import com.spring.gateway.settings.service.impl.SystemSettingAdminServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 存储对账调度的并发单元测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class ReconcileSchedulerUnitTests {
    private static final String CRON_A = "0 0 * * * *";
    private static final String CRON_B = "0 0 */2 * * *";
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final SystemSettingWriter settingWriter = mock(SystemSettingWriter.class);
    private final ThreadPoolTaskScheduler executor = mock(ThreadPoolTaskScheduler.class);
    private final ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
    private final ReconcileScheduler scheduler = new ReconcileScheduler(
            mock(StorageReconcileTask.class), settings, executor);

    ReconcileSchedulerUnitTests() {
        doReturn(scheduled).when(executor).schedule(any(Runnable.class), any(Trigger.class));
    }

    @AfterEach
    void cleanup() {
        scheduler.stop();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void startupReadsCommittedCronWithoutSnapshotGetter() {
        when(settings.loadStorageReconcileCron()).thenReturn(CRON_A);
        scheduler.start();
        assertEquals(List.of(CRON_A), expressions());
        verify(settings).loadStorageReconcileCron();
        verify(settings, never()).getStorageReconcileCron();
    }

    @Test
    void delayedEarlierCommitCallbackStillSchedulesLatestCommittedCron() throws Exception {
        java.util.Queue<Runnable> queued = new java.util.concurrent.ConcurrentLinkedQueue<>();
        doAnswer(invocation -> {
            queued.add(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        AtomicReference<String> committed = new AtomicReference<>();
        when(settings.loadStorageReconcileCron()).thenAnswer(invocation -> committed.get());
        SystemSettingAdminService controller = new SystemSettingAdminServiceImpl(
                settings, settingWriter, mock(MailCodeService.class), new UserProfileServiceImpl(mock(UserMapper.class)), scheduler);
        TransactionSynchronization callbackA = save(controller, CRON_A);
        committed.set(CRON_A);
        CountDownLatch delayed = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        FutureTask<Void> earlier = new FutureTask<>(() -> {
            delayed.countDown();
            await(releaseA);
            callbackA.afterCommit();
            return null;
        });
        Thread thread = new Thread(earlier, "delayed-commit-a");
        thread.start();
        try {
            await(delayed);
            TransactionSynchronization callbackB = save(controller, CRON_B);
            committed.set(CRON_B);
            callbackB.afterCommit();
            queued.remove().run();
            releaseA.countDown();
            earlier.get(5, TimeUnit.SECONDS);
            queued.remove().run();
            assertTrue(queued.isEmpty());
            assertEquals(List.of(CRON_B, CRON_B), expressions());
            verify(settingWriter).set(SystemSetting.KEY_STORAGE_RECONCILE_CRON, CRON_A);
            verify(settingWriter).set(SystemSetting.KEY_STORAGE_RECONCILE_CRON, CRON_B);
            verify(scheduled).cancel(false);
        } finally {
            releaseA.countDown();
            thread.join(5000);
        }
    }

    @Test
    void refreshSerializesDatabaseReadsWithRescheduling() throws Exception {
        AtomicReference<String> committed = new AtomicReference<>(CRON_A);
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch readingA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        when(settings.loadStorageReconcileCron()).thenAnswer(invocation -> {
            String value = committed.get();
            if (reads.incrementAndGet() == 1) {
                readingA.countDown();
                await(releaseA);
            }
            return value;
        });
        FutureTask<Void> first = new FutureTask<>(() -> { scheduler.refresh(); return null; });
        FutureTask<Void> second = new FutureTask<>(() -> { scheduler.refresh(); return null; });
        Thread threadA = new Thread(first, "refresh-a");
        Thread threadB = new Thread(second, "refresh-b");
        threadA.start();
        try {
            await(readingA);
            committed.set(CRON_B);
            threadB.start();
            awaitBlocked(threadB);
            assertEquals(1, reads.get());
            verifyNoInteractions(executor);
            releaseA.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(CRON_A, CRON_B), expressions());
        } finally {
            releaseA.countDown();
            threadA.join(5000);
            threadB.join(5000);
        }
    }

    @Test
    void databaseFailurePreservesExistingSchedule() {
        when(settings.loadStorageReconcileCron()).thenReturn(CRON_A);
        scheduler.refresh();
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(settings.loadStorageReconcileCron()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, scheduler::refresh));
        assertEquals(List.of(CRON_A), expressions());
        verifyNoInteractions(scheduled);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid", "0 */5 * * * *"})
    void missingInvalidOrTooFrequentCronUsesDefault(String cron) {
        when(settings.loadStorageReconcileCron()).thenReturn(cron);
        scheduler.refresh();
        assertEquals(List.of(SystemSettingService.DEFAULT_RECONCILE_CRON), expressions());
    }

    @Test
    void refreshAfterStopDoesNotReadOrReopenScheduler() {
        scheduler.stop();
        scheduler.refresh();
        verifyNoInteractions(settings);
        verify(executor).shutdown();
        verify(executor, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void commitCallbackOnlyQueuesAndDatabaseReadRunsOnWorker() throws Exception {
        Thread callbackThread = Thread.currentThread();
        when(settings.loadStorageReconcileCron()).thenAnswer(invocation -> {
            assertNotSame(callbackThread, Thread.currentThread());
            return CRON_B;
        });
        TransactionSynchronization callback = save(new SystemSettingAdminServiceImpl(
                settings, settingWriter, mock(MailCodeService.class), new UserProfileServiceImpl(mock(UserMapper.class)), scheduler), CRON_A);
        clearInvocations(settings);
        callback.afterCommit();
        verifyNoInteractions(settings);
        ArgumentCaptor<Runnable> queued = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(queued.capture());
        FutureTask<Void> work = new FutureTask<>(queued.getValue(), null);
        Thread worker = new Thread(work, "queued-refresh");
        worker.start();
        try {
            work.get(5, TimeUnit.SECONDS);
            verify(settings).loadStorageReconcileCron();
            assertEquals(List.of(CRON_B), expressions());
        } finally {
            worker.join(5000);
        }
    }

    @Test
    void requestRefreshDoesNotWaitForRefreshMonitor() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FutureTask<Void> holder = new FutureTask<>(() -> {
            synchronized (scheduler) {
                locked.countDown();
                await(release);
            }
            return null;
        });
        FutureTask<Void> request = new FutureTask<>(() -> {
            scheduler.requestRefresh();
            return null;
        });
        Thread holderThread = new Thread(holder, "refresh-monitor-holder");
        Thread requestThread = new Thread(request, "refresh-request");
        holderThread.start();
        try {
            await(locked);
            requestThread.start();
            request.get(2, TimeUnit.SECONDS);
            assertEquals(1, release.getCount());
            verify(executor).execute(any(Runnable.class));
            verifyNoInteractions(settings);
        } finally {
            release.countDown();
            holderThread.join(5000);
            requestThread.join(5000);
        }
        holder.get(5, TimeUnit.SECONDS);
    }

    @Test
    void refreshRequestFailuresAreContainedAndSanitized() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ReconcileScheduler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            doThrow(new java.util.concurrent.RejectedExecutionException("fixtureSecret"))
                    .when(executor).execute(any(Runnable.class));
            assertDoesNotThrow(scheduler::requestRefresh);
            verifyNoInteractions(settings);
            doNothing().when(executor).execute(any(Runnable.class));
            when(settings.loadStorageReconcileCron()).thenThrow(new IllegalStateException("fixtureSecret"));
            assertDoesNotThrow(scheduler::requestRefresh);
            ArgumentCaptor<Runnable> queued = ArgumentCaptor.forClass(Runnable.class);
            verify(executor, times(2)).execute(queued.capture());
            assertDoesNotThrow(queued.getAllValues().getLast()::run);
            assertEquals(List.of("存储对账调度刷新请求失败", "存储对账调度刷新失败"),
                    logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
            for (var event : logs.list) {
                assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel());
                assertNull(event.getThrowableProxy());
            }
            verify(executor, never()).schedule(any(Runnable.class), any(Trigger.class));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void queuedRefreshAndNewRequestsAfterStopDoNotReadOrReopenScheduler() {
        scheduler.requestRefresh();
        ArgumentCaptor<Runnable> queued = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(queued.capture());
        scheduler.stop();
        assertDoesNotThrow(queued.getValue()::run);
        assertDoesNotThrow(scheduler::requestRefresh);
        verify(executor).execute(any(Runnable.class));
        verifyNoInteractions(settings);
        verify(executor, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    private TransactionSynchronization save(SystemSettingAdminService controller, String cron) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            controller.setStorage(new AdminStorageSettingsRequest("local", null, null, null, null, cron));
            List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, callbacks.size());
            return callbacks.getFirst();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private List<String> expressions() {
        ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
        verify(executor, atLeastOnce()).schedule(any(Runnable.class), triggers.capture());
        return triggers.getAllValues().stream().map(trigger -> ((CronTrigger) trigger).getExpression()).toList();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "等待测试同步点超时");
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }
}
