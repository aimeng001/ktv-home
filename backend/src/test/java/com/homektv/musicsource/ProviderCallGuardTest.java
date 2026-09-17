package com.homektv.musicsource;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class ProviderCallGuardTest {
    @Test
    void enforcesConfiguredPerProviderConcurrency() {
        MusicSourceConfig config = new MusicSourceConfig(true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 0, 0.95);
        ProviderCallGuard guard = new ProviderCallGuard(() -> config);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        CompletableFuture<?> first = CompletableFuture.runAsync(() -> invoke(guard, active, maximum));
        CompletableFuture<?> second = CompletableFuture.runAsync(() -> invoke(guard, active, maximum));
        CompletableFuture.allOf(first, second).join();

        assertThat(maximum).hasValue(1);
    }

    @Test
    void spacesCallsToTheSameProvider() {
        MusicSourceConfig config = new MusicSourceConfig(true, Set.of(MusicProvider.QQ), 20, 5, 6, 2, 80, 0.95);
        ProviderCallGuard guard = new ProviderCallGuard(() -> config);
        long started = System.nanoTime();

        CompletableFuture<?> first = CompletableFuture.runAsync(() -> guard.call(MusicProvider.QQ, () -> null));
        CompletableFuture<?> second = CompletableFuture.runAsync(() -> guard.call(MusicProvider.QQ, () -> null));
        CompletableFuture.allOf(first, second).join();

        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(65);
    }

    @Test
    void persistentLimiterWrapsEveryExternalProviderCall() {
        MusicSourceConfig config = new MusicSourceConfig(true, Set.of(MusicProvider.QQ),
                20, 5, 6, 1, 1500, 0.95);
        ProviderRequestRateLimiter limiter = mock(ProviderRequestRateLimiter.class);
        ProviderCallGuard guard = new ProviderCallGuard(() -> config, limiter);

        assertThat(guard.call(MusicProvider.QQ, () -> "ok")).isEqualTo("ok");

        InOrder order = inOrder(limiter);
        order.verify(limiter).beforeRequest(MusicProvider.QQ);
        order.verify(limiter).recordSuccess(MusicProvider.QQ);
    }

    private static void invoke(ProviderCallGuard guard, AtomicInteger active, AtomicInteger maximum) {
        guard.call(MusicProvider.QQ, () -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(60);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return null;
        });
    }
}
