package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryScanProgressTest {
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-19T10:00:00+08:00");
    private static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-19T10:01:00+08:00");

    @Test
    void compatibilityConstructorDerivesEveryScanStateFromRunningFinishTimeAndPhase() {
        assertThat(progress(true, null, null).state()).isEqualTo(LibraryScanService.ScanState.RUNNING);
        assertThat(progress(false, FINISHED_AT, "FAILED: traversal interrupted").state())
                .isEqualTo(LibraryScanService.ScanState.FAILED);
        assertThat(progress(false, FINISHED_AT, null).state()).isEqualTo(LibraryScanService.ScanState.COMPLETED);
        assertThat(progress(false, null, "MEDIA_PROBE").state()).isEqualTo(LibraryScanService.ScanState.IDLE);
    }

    @Test
    void legacyConstructorDerivesPhaseForDiscoveryProbeIdleAndCompletedSnapshots() {
        var discovering = legacyProgress(true, 0, null);
        assertThat(discovering.phase()).isEqualTo("DISCOVERING");
        assertThat(discovering.state()).isEqualTo(LibraryScanService.ScanState.RUNNING);

        var probing = legacyProgress(true, 1, null);
        assertThat(probing.phase()).isEqualTo("MEDIA_PROBE");
        assertThat(probing.state()).isEqualTo(LibraryScanService.ScanState.RUNNING);

        var idle = legacyProgress(false, 0, null);
        assertThat(idle.phase()).isEqualTo("COMPLETED");
        assertThat(idle.state()).isEqualTo(LibraryScanService.ScanState.IDLE);

        var completed = legacyProgress(false, 0, FINISHED_AT);
        assertThat(completed.phase()).isEqualTo("COMPLETED");
        assertThat(completed.state()).isEqualTo(LibraryScanService.ScanState.COMPLETED);
    }

    private static LibraryScanService.ScanProgress progress(boolean running, OffsetDateTime finishedAt, String phase) {
        return new LibraryScanService.ScanProgress(running, 1, 0, null,
                0, 0, 0, 0, STARTED_AT, finishedAt, phase,
                1, 0, 0, 0, 0.0, null);
    }

    private static LibraryScanService.ScanProgress legacyProgress(boolean running, int total,
                                                                  OffsetDateTime finishedAt) {
        return new LibraryScanService.ScanProgress(running, total, 0, null,
                0, 0, 0, 0, STARTED_AT, finishedAt);
    }
}
