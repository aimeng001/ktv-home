package com.homektv.ai;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiConcurrencyLimiterTest {

    @Test
    void isolatesRolesAndReleasesEachPermitOnlyOnce() throws Exception {
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(config(1, 1));
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter(configService);
        AiConcurrencyLimiter.Permit bulk = limiter.acquire("BULK");
        AiConcurrencyLimiter.Permit reasoning = limiter.acquire("REASONING");

        AtomicReference<Thread> workerThread = new AtomicReference<>();
        ExecutorService worker = singleThreadExecutor(workerThread);
        try {
            CountDownLatch attemptingSecondBulk = new CountDownLatch(1);
            Future<AiConcurrencyLimiter.Permit> secondBulk = worker.submit(() -> {
                attemptingSecondBulk.countDown();
                return limiter.acquire("BULK");
            });
            assertTrue(attemptingSecondBulk.await(2, TimeUnit.SECONDS));
            awaitWaiting(workerThread);
            assertFalse(secondBulk.isDone());

            bulk.close();
            AiConcurrencyLimiter.Permit second = secondBulk.get(2, TimeUnit.SECONDS);
            bulk.close();

            CountDownLatch attemptingThirdBulk = new CountDownLatch(1);
            Future<AiConcurrencyLimiter.Permit> thirdBulk = worker.submit(() -> {
                attemptingThirdBulk.countDown();
                return limiter.acquire("BULK");
            });
            assertTrue(attemptingThirdBulk.await(2, TimeUnit.SECONDS));
            awaitWaiting(workerThread);
            assertFalse(thirdBulk.isDone());

            second.close();
            AiConcurrencyLimiter.Permit third = thirdBulk.get(2, TimeUnit.SECONDS);
            third.close();
            reasoning.close();
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void restoresInterruptAndReportsAnErrorWhenWaitingForABulkPermit() throws Exception {
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(config(1, 1));
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter(configService);
        AiConcurrencyLimiter.Permit held = limiter.acquire("BULK");

        AtomicReference<Thread> workerThread = new AtomicReference<>();
        ExecutorService worker = singleThreadExecutor(workerThread);
        try {
            CountDownLatch attempting = new CountDownLatch(1);
            Future<WaitResult> result = worker.submit(() -> {
                attempting.countDown();
                try {
                    limiter.acquire("BULK");
                    return new WaitResult(null, Thread.currentThread().isInterrupted());
                } catch (OpenAiCompatibleClient.AiProviderException error) {
                    return new WaitResult(error.getCode(), Thread.currentThread().isInterrupted());
                }
            });

            assertTrue(attempting.await(2, TimeUnit.SECONDS));
            awaitWaiting(workerThread);
            workerThread.get().interrupt();

            assertThat(result.get(2, TimeUnit.SECONDS))
                    .isEqualTo(new WaitResult("AI_INTERRUPTED", true));
        } finally {
            held.close();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static AiConfigService.ResolvedConfig config(int bulkConcurrency, int reasoningConcurrency) {
        return new AiConfigService.ResolvedConfig(true, "http://localhost", "bulk", "reasoning", 30,
                0.97, 0.92, AiConfigService.JsonMode.AUTO, bulkConcurrency, reasoningConcurrency, "");
    }

    private static ExecutorService singleThreadExecutor(AtomicReference<Thread> threadReference) {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "ai-limiter-test");
            threadReference.set(thread);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    private static void awaitWaiting(AtomicReference<Thread> threadReference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Thread thread = threadReference.get();
            if (thread != null && thread.getState() == Thread.State.WAITING) return;
            Thread.sleep(1);
        }
        assertThat(threadReference.get()).isNotNull();
        assertThat(threadReference.get().getState()).isEqualTo(Thread.State.WAITING);
    }

    private record WaitResult(String errorCode, boolean interrupted) { }
}
