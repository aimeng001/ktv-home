package com.homektv.library;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
}
