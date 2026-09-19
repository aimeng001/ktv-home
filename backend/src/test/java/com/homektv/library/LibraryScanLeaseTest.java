package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LibraryScanLeaseTest {

    @Test
    void heartbeatFailureFencesTheWorker() throws Exception {
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 3L);
        when(state.heartbeat(eq(claim), any())).thenReturn(true, false);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try (LibraryScanLease lease = new LibraryScanLease(
                claim,
                state,
                () -> new LibraryScanStateStore.ScanSnapshot("FAST_INDEX", 1, 1, 0, 0),
                scheduler,
                Duration.ofMillis(5))) {
            Thread.sleep(30);
            assertThatThrownBy(lease::assertOwned)
                    .isInstanceOf(LeaseLostException.class);
            assertThat(lease.isLost()).isTrue();
        } finally {
            scheduler.shutdownNow();
        }
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
}

