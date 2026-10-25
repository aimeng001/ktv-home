package com.homektv.resource;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceBudgetTest {
    @Test
    void rejectsEachInvalidProcessLimit() {
        assertThatThrownBy(() -> new ResourceBudget(0, 1, 1, 1, () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceBudget(1, 0, 1, 1, () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceBudget(1, 1, 0, 1, () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceBudget(1, 1, 1, 0, () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceBudget(1, 1, 1, 1, 0, () -> false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mediaStreamSlotsRejectAtCapacityAndCanBeReusedAfterIdempotentRelease() {
        ResourceBudget budget = new ResourceBudget(1, 1, 1, 1, 2, () -> false);
        ResourceBudget.Permit first = budget.tryAcquireMediaStream().orElseThrow();
        ResourceBudget.Permit second = budget.tryAcquireMediaStream().orElseThrow();
        ResourceBudget.Permit liveTranscode = budget.tryAcquireLiveTranscode().orElseThrow();

        assertThat(budget.activeMediaStreamCount()).isEqualTo(2);
        assertThat(budget.tryAcquireMediaStream()).isEmpty();

        first.close();
        first.close();
        assertThat(budget.activeMediaStreamCount()).isEqualTo(1);
        ResourceBudget.Permit reused = budget.tryAcquireMediaStream().orElseThrow();
        assertThat(budget.activeMediaStreamCount()).isEqualTo(2);
        reused.close();

        second.close();
        assertThat(budget.activeMediaStreamCount()).isZero();
        liveTranscode.close();
    }

    @Test
    void idleAdmitsTwoProbesAndThirdWaitsForAReleasedPermit() throws Exception {
        ResourceBudget budget = new ResourceBudget(2, 1, 4, () -> false);
        ResourceBudget.Permit first = budget.acquireProbe();
        ResourceBudget.Permit second = budget.acquireProbe();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<ResourceBudget.Permit> third = workers.submit(budget::acquireProbe);
            assertThatThrownBy(() -> third.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            first.close();
            ResourceBudget.Permit resumed = third.get(1, TimeUnit.SECONDS);
            assertThat(budget.activeProbeCount()).isEqualTo(2);
            resumed.close();
        } finally {
            first.close();
            second.close();
            workers.shutdownNow();
        }
        assertThat(budget.activeProbeCount()).isZero();
    }

    @Test
    void recordsProbeWaitAndClearsWaiterAfterPermitRelease() throws Exception {
        ResourceBudget budget = new ResourceBudget(1, 1, 4, () -> false);
        ResourceBudget.Permit held = budget.acquireProbe();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<ResourceBudget.Permit> waiting = workers.submit(budget::acquireProbe);
            ResourceBudget.WaitSnapshot beforeRelease = awaitWaiting(budget, false);
            assertThat(beforeRelease.active()).isEqualTo(1);
            assertThat(beforeRelease.waiting()).isEqualTo(1);
            assertThat(beforeRelease.limit()).isEqualTo(1);

            held.close();
            ResourceBudget.Permit acquired = waiting.get(1, TimeUnit.SECONDS);
            ResourceBudget.WaitSnapshot afterAcquire = budget.snapshot().probes();
            assertThat(afterAcquire.active()).isEqualTo(1);
            assertThat(afterAcquire.waiting()).isZero();
            assertThat(afterAcquire.waitCount()).isEqualTo(1);
            assertThat(afterAcquire.totalWaitNanos()).isPositive();
            assertThat(afterAcquire.maxWaitNanos()).isPositive();
            acquired.close();
        } finally {
            held.close();
            workers.shutdownNow();
        }
    }

    @Test
    void playingCapsNewProbeWorkAndAllowsAWaitingProbeToProgressAfterRelease() throws Exception {
        AtomicBoolean playing = new AtomicBoolean(false);
        ResourceBudget budget = new ResourceBudget(2, 1, 4, playing::get);
        ResourceBudget.Permit first = budget.acquireProbe();
        ResourceBudget.Permit second = budget.acquireProbe();
        playing.set(true);
        CountDownLatch waiting = new CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();

        try {
            Future<ResourceBudget.Permit> third = workers.submit(() -> {
                waiting.countDown();
                return budget.acquireProbe();
            });
            assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> third.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(budget.activeProbeCount()).isEqualTo(2);

            first.close();
            assertThatThrownBy(() -> third.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            second.close();
            ResourceBudget.Permit resumed = third.get(1, TimeUnit.SECONDS);
            assertThat(budget.activeProbeCount()).isEqualTo(1);
            resumed.close();
            assertThat(budget.activeProbeCount()).isZero();
        } finally {
            first.close();
            second.close();
            workers.shutdownNow();
        }
    }

    @Test
    void liveTranscodesHaveAnIndependentHardCapAndPermitCloseIsIdempotent() {
        ResourceBudget budget = new ResourceBudget(2, 1, 2, () -> false);
        ResourceBudget.Permit first = budget.tryAcquireLiveTranscode().orElseThrow();
        ResourceBudget.Permit second = budget.tryAcquireLiveTranscode().orElseThrow();

        assertThat(budget.tryAcquireLiveTranscode()).isEmpty();
        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(2);
        first.close();
        first.close();
        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);
        ResourceBudget.Permit reAdmitted = budget.tryAcquireLiveTranscode().orElseThrow();
        second.close();
        reAdmitted.close();
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void activeLiveTranscodeReducesProbeAdmissionAndReleaseRestoresIdleCapacity() throws Exception {
        ResourceBudget budget = new ResourceBudget(2, 1, 4, () -> false);
        ResourceBudget.Permit transcode = budget.tryAcquireLiveTranscode().orElseThrow();
        ResourceBudget.Permit firstProbe = budget.acquireProbe();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<ResourceBudget.Permit> secondProbe = workers.submit(budget::acquireProbe);
            assertThatThrownBy(() -> secondProbe.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            transcode.close();
            ResourceBudget.Permit admitted = secondProbe.get(1, TimeUnit.SECONDS);
            admitted.close();
        } finally {
            transcode.close();
            firstProbe.close();
            workers.shutdownNow();
        }
        assertThat(budget.activeProbeCount()).isZero();
    }

    @Test
    void activeManagedTranscodeReducesProbeAdmissionAndReleaseRestoresIdleCapacity() throws Exception {
        ResourceBudget budget = new ResourceBudget(2, 1, 4, () -> false);
        ResourceBudget.Permit transcode = budget.acquireManagedTranscode();
        ResourceBudget.Permit firstProbe = budget.acquireProbe();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<ResourceBudget.Permit> secondProbe = workers.submit(budget::acquireProbe);
            assertThatThrownBy(() -> secondProbe.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            transcode.close();
            ResourceBudget.Permit admitted = secondProbe.get(1, TimeUnit.SECONDS);
            assertThat(budget.activeManagedTranscodeCount()).isZero();
            admitted.close();
        } finally {
            transcode.close();
            firstProbe.close();
            workers.shutdownNow();
        }
        assertThat(budget.activeProbeCount()).isZero();
    }

    @Test
    void managedTranscodeLimitCanBeInterruptedWithoutLeakingCapacity() throws Exception {
        ResourceBudget budget = new ResourceBudget(2, 1, 4, 1, () -> false);
        ResourceBudget.Permit held = budget.acquireManagedTranscode();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch waiterFinished = new CountDownLatch(1);
        try {
            Future<ResourceBudget.Permit> blocked = workers.submit(() -> {
                waiting.countDown();
                try {
                    return budget.acquireManagedTranscode();
                } finally {
                    waiterFinished.countDown();
                }
            });
            assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitWaiting(budget, true).waiting()).isEqualTo(1);
            assertThatThrownBy(() -> blocked.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            blocked.cancel(true);
            assertThatThrownBy(blocked::get).isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(waiterFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(budget.activeManagedTranscodeCount()).isEqualTo(1);
        } finally {
            held.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(budget.activeManagedTranscodeCount()).isZero();
        ResourceBudget.WaitSnapshot interrupted = budget.snapshot().managedTranscodes();
        assertThat(interrupted.waiting()).isZero();
        assertThat(interrupted.waitCount()).isEqualTo(1);
        assertThat(interrupted.totalWaitNanos()).isPositive();
        assertThat(interrupted.maxWaitNanos()).isPositive();
        try (ResourceBudget.Permit available = budget.acquireManagedTranscode()) {
            assertThat(budget.activeManagedTranscodeCount()).isEqualTo(1);
        }
        assertThat(budget.activeManagedTranscodeCount()).isZero();
    }

    @Test
    void recordsManagedTranscodeWaitAndClearsWaiterAfterPermitRelease() throws Exception {
        ResourceBudget budget = new ResourceBudget(2, 1, 4, 1, () -> false);
        ResourceBudget.Permit held = budget.acquireManagedTranscode();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<ResourceBudget.Permit> waiting = workers.submit(budget::acquireManagedTranscode);
            ResourceBudget.WaitSnapshot beforeRelease = awaitWaiting(budget, true);
            assertThat(beforeRelease.active()).isEqualTo(1);
            assertThat(beforeRelease.waiting()).isEqualTo(1);
            assertThat(beforeRelease.limit()).isEqualTo(1);

            held.close();
            ResourceBudget.Permit acquired = waiting.get(1, TimeUnit.SECONDS);
            ResourceBudget.WaitSnapshot afterAcquire = budget.snapshot().managedTranscodes();
            assertThat(afterAcquire.active()).isEqualTo(1);
            assertThat(afterAcquire.waiting()).isZero();
            assertThat(afterAcquire.waitCount()).isEqualTo(1);
            assertThat(afterAcquire.totalWaitNanos()).isPositive();
            assertThat(afterAcquire.maxWaitNanos()).isPositive();
            acquired.close();
        } finally {
            held.close();
            workers.shutdownNow();
        }
    }

    @Test
    void managedWaiterRemainsRegisteredAcrossRepeatedCapacitySignals() throws Exception {
        ResourceBudget budget = new ResourceBudget(1, 1, 1, 1, () -> false);
        ResourceBudget.Permit held = budget.acquireManagedTranscode();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<ResourceBudget.Permit> waiting = workers.submit(budget::acquireManagedTranscode);
            assertThat(awaitWaiting(budget, true).waiting()).isEqualTo(1);

            for (int attempt = 0; attempt < 3; attempt++) {
                budget.signalWaiters();
                Thread.sleep(20);
                ResourceBudget.WaitSnapshot snapshot = budget.snapshot().managedTranscodes();
                assertThat(snapshot.active()).isEqualTo(1);
                assertThat(snapshot.waiting()).isEqualTo(1);
            }

            held.close();
            ResourceBudget.Permit acquired = waiting.get(1, TimeUnit.SECONDS);
            assertThat(budget.snapshot().managedTranscodes().waitCount()).isEqualTo(1);
            acquired.close();
        } finally {
            held.close();
            workers.shutdownNow();
        }
        assertThat(budget.activeManagedTranscodeCount()).isZero();
    }

    @Test
    void interruptedWaitDoesNotConsumeAProbePermit() throws Exception {
        ResourceBudget budget = new ResourceBudget(1, 1, 4, () -> false);
        ResourceBudget.Permit held = budget.acquireProbe();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch waiterFinished = new CountDownLatch(1);
        try {
            Future<?> blocked = workers.submit(() -> {
                waiting.countDown();
                try {
                    return budget.acquireProbe();
                } finally {
                    waiterFinished.countDown();
                }
            });
            assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitWaiting(budget, false).waiting()).isEqualTo(1);
            blocked.cancel(true);
            assertThatThrownBy(blocked::get).isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(waiterFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(budget.activeProbeCount()).isEqualTo(1);
        } finally {
            held.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(budget.activeProbeCount()).isZero();
        ResourceBudget.WaitSnapshot interrupted = budget.snapshot().probes();
        assertThat(interrupted.waiting()).isZero();
        assertThat(interrupted.waitCount()).isEqualTo(1);
        assertThat(interrupted.totalWaitNanos()).isPositive();
        assertThat(interrupted.maxWaitNanos()).isPositive();
    }

    private static ResourceBudget.WaitSnapshot awaitWaiting(ResourceBudget budget, boolean managed)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        ResourceBudget.WaitSnapshot snapshot = managed
                ? budget.snapshot().managedTranscodes()
                : budget.snapshot().probes();
        while (snapshot.waiting() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1);
            snapshot = managed ? budget.snapshot().managedTranscodes() : budget.snapshot().probes();
        }
        return snapshot;
    }
}
