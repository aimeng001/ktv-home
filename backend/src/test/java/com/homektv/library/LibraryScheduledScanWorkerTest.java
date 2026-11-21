package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.resource.PlaybackPressureMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class LibraryScheduledScanWorkerTest {

    @Test
    void skipsWhenNotExternalReadOnly(@TempDir Path tempDir) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.MANAGED);
        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        LibraryScanStateStore stateStore = mock(LibraryScanStateStore.class);
        PlaybackPressureMonitor monitor = mock(PlaybackPressureMonitor.class);

        LibraryScheduledScanWorker worker = new LibraryScheduledScanWorker(props, coordinator, stateStore, monitor);
        worker.scheduledTick();

        verifyNoInteractions(coordinator);
    }

    @Test
    void skipsWhenScanActive(@TempDir Path tempDir) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        props.setSourceLibraryPath(tempDir.toString());
        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        when(coordinator.hasActiveScan()).thenReturn(true);
        LibraryScanStateStore stateStore = mock(LibraryScanStateStore.class);
        PlaybackPressureMonitor monitor = mock(PlaybackPressureMonitor.class);

        LibraryScheduledScanWorker worker = new LibraryScheduledScanWorker(props, coordinator, stateStore, monitor);
        worker.scheduledTick();

        verify(coordinator, never()).requestScan();
    }

    @Test
    void skipsWhenPlaybackBusy(@TempDir Path tempDir) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        props.setSourceLibraryPath(tempDir.toString());
        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        when(coordinator.hasActiveScan()).thenReturn(false);
        LibraryScanStateStore stateStore = mock(LibraryScanStateStore.class);
        PlaybackPressureMonitor monitor = mock(PlaybackPressureMonitor.class);
        when(monitor.isPlaybackBusy()).thenReturn(true);

        LibraryScheduledScanWorker worker = new LibraryScheduledScanWorker(props, coordinator, stateStore, monitor);
        worker.scheduledTick();

        verify(coordinator, never()).requestScan();
    }

    @Test
    void skipsWhenCycleNotReached(@TempDir Path tempDir) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        props.setSourceLibraryPath(tempDir.toString());
        props.getScan().setScheduleMinutes(60);

        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        when(coordinator.hasActiveScan()).thenReturn(false);
        PlaybackPressureMonitor monitor = mock(PlaybackPressureMonitor.class);
        when(monitor.isPlaybackBusy()).thenReturn(false);

        LibraryScanStateStore stateStore = mock(LibraryScanStateStore.class);
        // 上次更新在 10 分钟前
        LibraryScanStateStore.Snapshot snapshot = new LibraryScanStateStore.Snapshot(
                "active", tempDir.toString(), "identity", "EXTERNAL_READ_ONLY", 1,
                LibraryScanStateStore.State.COMPLETED, "IDLE", UUID.randomUUID(), UUID.randomUUID(),
                1L, 100L, 100L, 100L, 0L, null, null,
                OffsetDateTime.now().minusMinutes(10));
        when(stateStore.find()).thenReturn(Optional.of(snapshot));

        LibraryScheduledScanWorker worker = new LibraryScheduledScanWorker(props, coordinator, stateStore, monitor);
        worker.scheduledTick();

        verify(coordinator, never()).requestScan();
    }

    @Test
    void triggersScanWhenCycleReachedAndRootValid(@TempDir Path tempDir) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        props.setSourceLibraryPath(tempDir.toString());
        props.getScan().setScheduleMinutes(60);

        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        when(coordinator.hasActiveScan()).thenReturn(false);
        PlaybackPressureMonitor monitor = mock(PlaybackPressureMonitor.class);
        when(monitor.isPlaybackBusy()).thenReturn(false);

        LibraryScanStateStore stateStore = mock(LibraryScanStateStore.class);
        // 上次更新在 120 分钟前
        LibraryScanStateStore.Snapshot snapshot = new LibraryScanStateStore.Snapshot(
                "active", tempDir.toString(), "identity", "EXTERNAL_READ_ONLY", 1,
                LibraryScanStateStore.State.COMPLETED, "IDLE", UUID.randomUUID(), UUID.randomUUID(),
                1L, 100L, 100L, 100L, 0L, null, null,
                OffsetDateTime.now().minusMinutes(120));
        when(stateStore.find()).thenReturn(Optional.of(snapshot));

        LibraryScheduledScanWorker worker = new LibraryScheduledScanWorker(props, coordinator, stateStore, monitor);
        worker.scheduledTick();

        verify(coordinator).requestScan();
    }

    @Test
    void triggersScanWhenPreviousScanFailedAndCycleReached(@TempDir Path tempDir) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        props.setSourceLibraryPath(tempDir.toString());
        props.getScan().setScheduleMinutes(60);

        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        when(coordinator.hasActiveScan()).thenReturn(false);
        PlaybackPressureMonitor monitor = mock(PlaybackPressureMonitor.class);
        when(monitor.isPlaybackBusy()).thenReturn(false);

        LibraryScanStateStore stateStore = mock(LibraryScanStateStore.class);
        // 上次失败在 120 分钟前
        LibraryScanStateStore.Snapshot snapshot = new LibraryScanStateStore.Snapshot(
                "active", tempDir.toString(), "identity", "EXTERNAL_READ_ONLY", 1,
                LibraryScanStateStore.State.FAILED, "FAILED", UUID.randomUUID(), UUID.randomUUID(),
                1L, 100L, 100L, 100L, 0L, "SCAN_FAILED", "瞬时网络中断",
                OffsetDateTime.now().minusMinutes(120));
        when(stateStore.find()).thenReturn(Optional.of(snapshot));

        LibraryScheduledScanWorker worker = new LibraryScheduledScanWorker(props, coordinator, stateStore, monitor);
        worker.scheduledTick();

        verify(coordinator).requestScan();
    }

    @Test
    void skipsScanWhenPreviousScanFailedButCycleNotReached(@TempDir Path tempDir) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        props.setSourceLibraryPath(tempDir.toString());
        props.getScan().setScheduleMinutes(60);

        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        when(coordinator.hasActiveScan()).thenReturn(false);
        PlaybackPressureMonitor monitor = mock(PlaybackPressureMonitor.class);
        when(monitor.isPlaybackBusy()).thenReturn(false);

        LibraryScanStateStore stateStore = mock(LibraryScanStateStore.class);
        // 上次失败仅在 10 分钟前（未达到 60 分钟退避周期）
        LibraryScanStateStore.Snapshot snapshot = new LibraryScanStateStore.Snapshot(
                "active", tempDir.toString(), "identity", "EXTERNAL_READ_ONLY", 1,
                LibraryScanStateStore.State.FAILED, "FAILED", UUID.randomUUID(), UUID.randomUUID(),
                1L, 100L, 100L, 100L, 0L, "SCAN_FAILED", "瞬时网络中断",
                OffsetDateTime.now().minusMinutes(10));
        when(stateStore.find()).thenReturn(Optional.of(snapshot));

        LibraryScheduledScanWorker worker = new LibraryScheduledScanWorker(props, coordinator, stateStore, monitor);
        worker.scheduledTick();

        verify(coordinator, never()).requestScan();
    }

    @Test
    void triggersScanWhenPreviousScanPartialAndCycleReached(@TempDir Path tempDir) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        props.setSourceLibraryPath(tempDir.toString());
        props.getScan().setScheduleMinutes(60);

        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        when(coordinator.hasActiveScan()).thenReturn(false);
        PlaybackPressureMonitor monitor = mock(PlaybackPressureMonitor.class);
        when(monitor.isPlaybackBusy()).thenReturn(false);

        LibraryScanStateStore stateStore = mock(LibraryScanStateStore.class);
        // 上次 PARTIAL 在 90 分钟前
        LibraryScanStateStore.Snapshot snapshot = new LibraryScanStateStore.Snapshot(
                "active", tempDir.toString(), "identity", "EXTERNAL_READ_ONLY", 1,
                LibraryScanStateStore.State.PARTIAL, "PARTIAL", UUID.randomUUID(), UUID.randomUUID(),
                1L, 100L, 100L, 100L, 0L, null, null,
                OffsetDateTime.now().minusMinutes(90));
        when(stateStore.find()).thenReturn(Optional.of(snapshot));

        LibraryScheduledScanWorker worker = new LibraryScheduledScanWorker(props, coordinator, stateStore, monitor);
        worker.scheduledTick();

        verify(coordinator).requestScan();
    }
}
