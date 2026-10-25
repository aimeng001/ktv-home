package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LibraryScanLeaseTest {

    @Test
    void scheduledHeartbeatFailureFencesTheWorkerDeterministically() {
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 3L);
        when(state.heartbeat(eq(claim), any())).thenReturn(true, false);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeatTask = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledHeartbeat = new AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    scheduledHeartbeat.set(invocation.getArgument(0));
                    return heartbeatTask;
                });
        try (LibraryScanLease lease = new LibraryScanLease(
                claim,
                state,
                () -> new LibraryScanStateStore.ScanSnapshot("FAST_INDEX", 1, 1, 0, 0),
                scheduler,
                Duration.ofSeconds(5))) {
            scheduledHeartbeat.get().run();
            scheduledHeartbeat.get().run();
            assertThatThrownBy(lease::assertOwned)
                    .isInstanceOf(LeaseLostException.class);
            assertThat(lease.isLost()).isTrue();
            verify(state, times(2)).heartbeat(eq(claim), any());
        }
        verify(heartbeatTask).cancel(false);
    }

    @Test
    void explicitOwnershipCheckRefreshesBeforeAWrite() {
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 4L);
        when(state.heartbeat(eq(claim), any())).thenReturn(true);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try (LibraryScanLease lease = new LibraryScanLease(
                claim,
                state,
                () -> new LibraryScanStateStore.ScanSnapshot("MEDIA_PROBE", 2, 2, 1, 0),
                scheduler,
                Duration.ofHours(1))) {
            lease.assertOwned();
            verify(state).heartbeat(eq(claim), any());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void nullZeroAndNegativeHeartbeatIntervalsUseTheDefaultPeriod() {
        assertDefaultHeartbeatInterval(null);
        assertDefaultHeartbeatInterval(Duration.ZERO);
        assertDefaultHeartbeatInterval(Duration.ofSeconds(-1));
    }

    @Test
    void heartbeatLossDuringOwnershipCheckFencesTheWorkerAndStopsFurtherRefreshes() {
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 5L);
        when(state.heartbeat(eq(claim), any())).thenReturn(false);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeatTask = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledHeartbeat = new AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    scheduledHeartbeat.set(invocation.getArgument(0));
                    return heartbeatTask;
                });

        try (LibraryScanLease lease = new LibraryScanLease(
                claim, state, () -> new LibraryScanStateStore.ScanSnapshot("FAST_INDEX", 1, 1, 0, 0),
                scheduler, Duration.ofSeconds(5))) {
            assertThatThrownBy(lease::assertOwned).isInstanceOf(LeaseLostException.class);
            assertThat(lease.isLost()).isTrue();

            scheduledHeartbeat.get().run();
            verify(state, times(1)).heartbeat(eq(claim), any());
        }
        verify(heartbeatTask).cancel(false);
    }

    @Test
    void heartbeatStoreExceptionFencesTheWorker() {
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 6L);
        when(state.heartbeat(eq(claim), any())).thenThrow(new IllegalStateException("database unavailable"));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));

        try (LibraryScanLease lease = new LibraryScanLease(
                claim, state, () -> new LibraryScanStateStore.ScanSnapshot("MEDIA_PROBE", 1, 1, 0, 0),
                scheduler, Duration.ofSeconds(5))) {
            assertThatThrownBy(lease::assertOwned).isInstanceOf(LeaseLostException.class);
            assertThat(lease.isLost()).isTrue();
        }
        verify(state).heartbeat(eq(claim), any());
    }

    private static void assertDefaultHeartbeatInterval(Duration interval) {
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 7L);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeatTask = mock(ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> heartbeatTask);

        try (LibraryScanLease ignored = new LibraryScanLease(
                claim, state, () -> new LibraryScanStateStore.ScanSnapshot("FAST_INDEX", 0, 0, 0, 0),
                scheduler, interval)) {
            verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(30_000L), eq(30_000L),
                    eq(TimeUnit.MILLISECONDS));
        }
        verify(heartbeatTask).cancel(false);
    }
}

