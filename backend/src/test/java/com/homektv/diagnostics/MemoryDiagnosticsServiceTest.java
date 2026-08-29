package com.homektv.diagnostics;

import com.homektv.library.LibraryScanService;
import org.junit.jupiter.api.Test;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryDiagnosticsServiceTest {

    @Test
    void snapshotExposesHeapNonHeapAndScanStateWithoutPathsOrSecrets() {
        MemoryMXBean memory = mock(MemoryMXBean.class);
        when(memory.getHeapMemoryUsage()).thenReturn(new MemoryUsage(0, 128, 768, 2048));
        when(memory.getNonHeapMemoryUsage()).thenReturn(new MemoryUsage(0, 128, 256, -1));
        LibraryScanService.ScanProgress progress = new LibraryScanService.ScanProgress(
                true, 48_122, 17_500, "secret-source/song.mkv", 2, 3, 4, 5,
                OffsetDateTime.parse("2026-08-29T12:00:00Z"), null,
                "MEDIA_PROBE", 48_122, 48_122, 64, 17_500);

        MemoryDiagnosticsService service = new MemoryDiagnosticsService(() -> progress, memory);

        MemoryDiagnostics diagnostics = service.snapshot();

        assertThat(diagnostics.heapUsedBytes()).isEqualTo(128);
        assertThat(diagnostics.heapCommittedBytes()).isEqualTo(768);
        assertThat(diagnostics.heapMaxBytes()).isEqualTo(2048);
        assertThat(diagnostics.nonHeapUsedBytes()).isEqualTo(128);
        assertThat(diagnostics.scanRunning()).isTrue();
        assertThat(diagnostics.scanPhase()).isEqualTo("MEDIA_PROBE");
        assertThat(diagnostics.discoveredFiles()).isEqualTo(48_122);
        assertThat(diagnostics.probeProcessed()).isEqualTo(17_500);
        assertThat(diagnostics.toString()).doesNotContain("secret-source");
    }

    @Test
    void unknownMemoryMaximumIsReportedAsZeroAndMissingScanIsIdle() {
        MemoryMXBean memory = mock(MemoryMXBean.class);
        when(memory.getHeapMemoryUsage()).thenReturn(new MemoryUsage(0, 1, 2, -1));
        when(memory.getNonHeapMemoryUsage()).thenReturn(new MemoryUsage(0, 3, 4, -1));

        MemoryDiagnostics diagnostics = new MemoryDiagnosticsService(() -> null, memory).snapshot();

        assertThat(diagnostics.heapMaxBytes()).isZero();
        assertThat(diagnostics.scanRunning()).isFalse();
        assertThat(diagnostics.scanPhase()).isEqualTo("IDLE");
        assertThat(diagnostics.discoveredFiles()).isZero();
        assertThat(diagnostics.probeProcessed()).isZero();
    }
}
