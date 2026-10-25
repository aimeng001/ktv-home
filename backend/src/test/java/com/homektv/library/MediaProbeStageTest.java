package com.homektv.library;

import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.media.MediaProbeException;
import com.homektv.resource.ResourceBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaProbeStageTest {
    @Test
    void rejectsMissingDependenciesAtConstruction() {
        FFprobeService ffprobe = mock(FFprobeService.class);
        ResourceBudget budget = new ResourceBudget(1, 1, 4, () -> false);

        assertThatThrownBy(() -> new MediaProbeStage(null, budget))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ffprobe");
        assertThatThrownBy(() -> new MediaProbeStage(ffprobe, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("resourceBudget");
    }

    @Test
    void returnsProbeOutcomeAndReleasesPermit() {
        FFprobeService ffprobe = mock(FFprobeService.class);
        Path file = Path.of("song.mkv");
        MediaProbe expected = new MediaProbe(120_000, 1, 0, true, "1920x1080", java.util.List.of(), "h264", "aac");
        when(ffprobe.probe(file)).thenReturn(expected);
        ResourceBudget budget = new ResourceBudget(1, 1, 4, () -> false);

        MediaProbeStage.Result result = new MediaProbeStage(ffprobe, budget).probe(file);

        assertThat(result.probe()).isEqualTo(expected);
        assertThat(result.failure()).isNull();
        assertThat(result.attempted()).isTrue();
        assertThat(budget.activeProbeCount()).isZero();
    }

    @Test
    void probeFailureIsReturnedAndDoesNotLeakPermit() {
        FFprobeService ffprobe = mock(FFprobeService.class);
        Path file = Path.of("bad.mkv");
        when(ffprobe.probe(file)).thenThrow(new MediaProbeException("bad media"));
        ResourceBudget budget = new ResourceBudget(1, 1, 4, () -> false);

        MediaProbeStage.Result result = new MediaProbeStage(ffprobe, budget).probe(file);

        assertThat(result.probe()).isNull();
        assertThat(result.failure()).isInstanceOf(MediaProbeException.class);
        assertThat(result.attempted()).isTrue();
        assertThat(budget.activeProbeCount()).isZero();
    }

    @Test
    void requiredProbePropagatesFailureAndReleasesPermit() {
        FFprobeService ffprobe = mock(FFprobeService.class);
        Path file = Path.of("bad-required-probe.mkv");
        MediaProbeException failure = new MediaProbeException("bad required media");
        when(ffprobe.probe(file)).thenThrow(failure);
        ResourceBudget budget = new ResourceBudget(1, 1, 4, () -> false);

        assertThatThrownBy(() -> new MediaProbeStage(ffprobe, budget).probeRequired(file))
                .isSameAs(failure);

        assertThat(budget.activeProbeCount()).isZero();
    }

    @Test
    void interruptedBudgetWaitReturnsDeferredOutcomeAndRestoresInterrupt() throws Exception {
        FFprobeService ffprobe = mock(FFprobeService.class);
        ResourceBudget budget = new ResourceBudget(1, 1, 4, () -> false);
        ResourceBudget.Permit held = budget.acquireProbe();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<MediaProbeStage.Result> result = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            started.countDown();
            result.set(new MediaProbeStage(ffprobe, budget).probe(Path.of("song.mkv")));
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });

        try {
            worker.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (budget.snapshot().probes().waiting() == 0
                    && worker.isAlive() && System.nanoTime() < waitDeadline) {
                Thread.onSpinWait();
            }
            assertThat(budget.snapshot().probes().waiting()).isEqualTo(1);
            worker.interrupt();
            worker.join(1_000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(result.get().failure()).isInstanceOf(InterruptedException.class);
            assertThat(result.get().attempted()).isFalse();
            assertThat(interruptRestored.get()).isTrue();
            assertThat(budget.activeProbeCount()).isEqualTo(1);
            verify(ffprobe, never()).probe(Path.of("song.mkv"));
        } finally {
            held.close();
            worker.interrupt();
            worker.join(1_000);
        }
        assertThat(budget.activeProbeCount()).isZero();
    }
}
