package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LibraryScanCoordinatorTest {

    @Test
    void bootstrapSubmitsOneBackgroundScanAndPersistsCompletion() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanService scan = mock(LibraryScanService.class);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 1L);
        when(state.needsBootstrap(props)).thenReturn(true);
        when(state.tryClaim(props, "BOOTSTRAP")).thenReturn(Optional.of(claim));
        when(scan.scanAllWithLease(any())).thenReturn(new LibraryScanService.ScanResult(4, 4, 0, 0, 0));

        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(
                props, scan, state, submitted::set);

        assertThat(coordinator.requestBootstrap()).isTrue();
        assertThat(coordinator.requestBootstrap()).isFalse();
        verify(scan, never()).scanAll();

        submitted.get().run();

        verify(scan).scanAllWithLease(any());
        verify(state).markCompleted(eq(claim), any(), any());
    }


    @Test
    void lostCompletionDoesNotPublishSuccess() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanService scan = mock(LibraryScanService.class);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 2L);
        when(state.tryClaim(props, "MANUAL_OR_WATCH")).thenReturn(Optional.of(claim));
        when(scan.scanAllWithLease(any())).thenReturn(
                new LibraryScanService.ScanResult(1, 1, 0, 0, 0));
        when(state.markCompleted(eq(claim), any(), any())).thenReturn(false);

        AtomicReference<LibraryScanService.ScanResult> callback = new AtomicReference<>();
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(
                props, scan, state, submitted::set);

        assertThat(coordinator.requestScan(callback::set)).isTrue();
        submitted.get().run();

        assertThat(callback).hasValue(null);
        verify(state).markCompleted(eq(claim), any(), any());
    }

    @Test
    void managedModeDoesNotSubmitAutomaticExternalScan() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.MANAGED);
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(
                props, mock(LibraryScanService.class), mock(LibraryScanStateStore.class), ignored -> {
                    throw new AssertionError("managed mode must not schedule external scan");
                });

        assertThat(coordinator.requestBootstrap()).isFalse();
    }

    @Test
    void bootstrapDoesNotClaimWhenTheExternalLibraryIsAlreadyBootstrapped() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        when(state.needsBootstrap(props)).thenReturn(false);
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(props,
                mock(LibraryScanService.class), state, ignored -> {
                    throw new AssertionError("already bootstrapped libraries must not be scanned");
                });

        assertThat(coordinator.requestBootstrap()).isFalse();
        verify(state, never()).tryClaim(any(), any());
    }

    @Test
    void manualScanAndRebindAreDisabledOutsideExternalReadOnlyMode() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.MANAGED);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(props,
                mock(LibraryScanService.class), state, ignored -> {
                    throw new AssertionError("managed libraries must not schedule external scans");
                });

        assertThat(coordinator.requestScan()).isFalse();
        assertThat(coordinator.rebindCurrentRoot()).isFalse();
        verify(state, never()).rebind(any());
    }

    @Test
    void externalRootRebindUsesTheStateStoreDecision() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        when(state.rebind(props)).thenReturn(false, true);
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(props,
                mock(LibraryScanService.class), state, ignored -> {});

        assertThat(coordinator.rebindCurrentRoot()).isFalse();
        assertThat(coordinator.rebindCurrentRoot()).isTrue();
        verify(state, times(2)).rebind(props);
    }

    @Test
    void emptyClaimAndClaimFailureReleaseTheSchedulingGuardForRetry() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        LibraryScanStateStore.Claim claim = claim(3L);
        when(state.tryClaim(props, "MANUAL_OR_WATCH"))
                .thenReturn(Optional.empty())
                .thenThrow(new IllegalStateException("synthetic database outage"))
                .thenReturn(Optional.of(claim));
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(props,
                mock(LibraryScanService.class), state, submitted::set);

        assertThat(coordinator.requestScan()).isFalse();
        assertThat(coordinator.requestScan()).isFalse();
        assertThat(coordinator.requestScan()).isTrue();
        assertThat(submitted.get()).isNotNull();
    }

    @Test
    void rejectedExecutorMarksTheClaimFailedAndReleasesTheSchedulingGuard() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        LibraryScanStateStore.Claim claim = claim(4L);
        when(state.tryClaim(props, "MANUAL_OR_WATCH")).thenReturn(Optional.of(claim));
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(props,
                mock(LibraryScanService.class), state, ignored -> {
                    throw new RejectedExecutionException("synthetic shutdown");
                });

        assertThat(coordinator.requestScan()).isFalse();
        assertThat(coordinator.requestScan()).isFalse();

        verify(state, org.mockito.Mockito.times(2)).markFailed(eq(claim),
                eq("SCAN_EXECUTOR_REJECTED"), eq("synthetic shutdown"));
        verify(state, org.mockito.Mockito.times(2)).tryClaim(props, "MANUAL_OR_WATCH");
    }

    @Test
    void apiAndRuntimeFailuresPersistTheirCodesAndReleaseTheSchedulingGuard() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanService scan = mock(LibraryScanService.class);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        LibraryScanStateStore.Claim claim = claim(5L);
        when(state.tryClaim(props, "MANUAL_OR_WATCH")).thenReturn(Optional.of(claim));
        when(scan.scanAllWithLease(any()))
                .thenThrow(new ApiException("SCAN_CONFLICT", "synthetic conflict"))
                .thenThrow(new IllegalStateException("synthetic scanner failure"));
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(props, scan, state, submitted::set);

        assertThat(coordinator.requestScan()).isTrue();
        submitted.get().run();
        assertThat(coordinator.requestScan()).isTrue();
        submitted.get().run();

        verify(state).markFailed(claim, "SCAN_CONFLICT", "synthetic conflict");
        verify(state).markFailed(claim, "SCAN_RUNTIME_ERROR", "synthetic scanner failure");
    }

    @Test
    void successfulCompletionPublishesTheCallbackResult() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanService scan = mock(LibraryScanService.class);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        LibraryScanStateStore.Claim claim = claim(6L);
        LibraryScanService.ScanResult expected = new LibraryScanService.ScanResult(2, 2, 0, 0, 0);
        when(state.tryClaim(props, "MANUAL_OR_WATCH")).thenReturn(Optional.of(claim));
        when(scan.scanAllWithLease(any())).thenReturn(expected);
        when(state.markCompleted(eq(claim), eq(expected), any())).thenReturn(true);
        AtomicReference<LibraryScanService.ScanResult> callback = new AtomicReference<>();
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(props, scan, state, submitted::set);

        assertThat(coordinator.requestScan(callback::set)).isTrue();
        submitted.get().run();

        assertThat(callback).hasValue(expected);
    }

    @Test
    void successfulCompletionWithoutCallbackStillPersistsTheResult() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanService scan = mock(LibraryScanService.class);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        LibraryScanStateStore.Claim claim = claim(7L);
        LibraryScanService.ScanResult expected = new LibraryScanService.ScanResult(2, 2, 0, 0, 0);
        when(state.tryClaim(props, "MANUAL_OR_WATCH")).thenReturn(Optional.of(claim));
        when(state.markCompleted(eq(claim), eq(expected), any())).thenReturn(true);
        when(scan.scanAllWithLease(any())).thenReturn(expected);
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(props, scan, state, submitted::set);

        assertThat(coordinator.requestScan()).isTrue();
        submitted.get().run();

        verify(state).markCompleted(eq(claim), eq(expected), any());
    }

    @Test
    void lostLeaseDoesNotMarkTheScanFailedAndReleasesTheSchedulingGuard() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanService scan = mock(LibraryScanService.class);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        LibraryScanStateStore.Claim claim = claim(8L);
        when(state.tryClaim(props, "MANUAL_OR_WATCH")).thenReturn(Optional.of(claim));
        when(scan.scanAllWithLease(any())).thenThrow(new LeaseLostException("synthetic lease loss"));
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(
                props, scan, state, submitted::set, heartbeat);

        try {
            assertThat(coordinator.requestScan()).isTrue();
            submitted.get().run();
            assertThat(coordinator.requestScan()).isTrue();
            verify(state, never()).markFailed(any(), any(), any());
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void shutdownClosesOwnedExecutorsAndAcceptsExternallyManagedResources() {
        AppProperties props = new AppProperties();
        ExecutorService owned = Executors.newSingleThreadExecutor();
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        LibraryScanCoordinator owning = new LibraryScanCoordinator(props,
                mock(LibraryScanService.class), mock(LibraryScanStateStore.class), owned, heartbeat);

        owning.shutdown();

        assertThat(owned.isShutdown()).isTrue();
        assertThat(heartbeat.isShutdown()).isTrue();

        LibraryScanCoordinator external = new LibraryScanCoordinator(props,
                mock(LibraryScanService.class), mock(LibraryScanStateStore.class), Runnable::run, null);
        external.shutdown();
    }

    private static LibraryScanStateStore.Claim claim(long generation) {
        return new LibraryScanStateStore.Claim(UUID.randomUUID(), UUID.randomUUID(), generation);
    }
}
