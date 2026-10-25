package com.homektv.resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Coordinates admission to expensive local media work without sharing task executors. */
@Component
public final class ResourceBudget {
    private static final long PRESSURE_RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private final int idleProbeLimit;
    private final int playingProbeLimit;
    private final int liveTranscodeLimit;
    private final int managedTranscodeLimit;
    private final int mediaStreamLimit;
    private final BooleanSupplier playbackBusy;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition capacityChanged = lock.newCondition();
    private int activeProbes;
    private int activeLiveTranscodes;
    private int activeManagedTranscodes;
    private int activeMediaStreams;
    private int waitingProbes;
    private int waitingManagedTranscodes;
    private final WaitStats probeWaitStats = new WaitStats();
    private final WaitStats managedTranscodeWaitStats = new WaitStats();

    @Autowired
    public ResourceBudget(
            PlaybackPressureMonitor pressureMonitor,
            @Value("${app.resource-budget.probe-idle-limit:2}") int idleProbeLimit,
            @Value("${app.resource-budget.probe-playing-limit:1}") int playingProbeLimit,
            @Value("${app.resource-budget.live-transcode-limit:4}") int liveTranscodeLimit,
            @Value("${app.resource-budget.managed-transcode-limit:1}") int managedTranscodeLimit,
            @Value("${app.resource-budget.media-stream-limit:4}") int mediaStreamLimit) {
        this(idleProbeLimit, playingProbeLimit, liveTranscodeLimit, managedTranscodeLimit, mediaStreamLimit,
                Objects.requireNonNull(pressureMonitor, "pressureMonitor")::isPlaybackBusy);
        pressureMonitor.addChangeListener(this::signalWaiters);
    }

    /** Constructor for isolated tests and non-Spring fixtures. */
    public ResourceBudget(int idleProbeLimit, int playingProbeLimit, int liveTranscodeLimit,
                          BooleanSupplier playbackBusy) {
        this(idleProbeLimit, playingProbeLimit, liveTranscodeLimit, 1, 4, playbackBusy);
    }

    /** Constructor with explicit limits for all process categories. */
    public ResourceBudget(int idleProbeLimit, int playingProbeLimit, int liveTranscodeLimit,
                          int managedTranscodeLimit, BooleanSupplier playbackBusy) {
        this(idleProbeLimit, playingProbeLimit, liveTranscodeLimit, managedTranscodeLimit, 4, playbackBusy);
    }

    /** Constructor with explicit limits for all resource categories. */
    public ResourceBudget(int idleProbeLimit, int playingProbeLimit, int liveTranscodeLimit,
                          int managedTranscodeLimit, int mediaStreamLimit, BooleanSupplier playbackBusy) {
        if (idleProbeLimit < 1 || playingProbeLimit < 1 || liveTranscodeLimit < 1
                || managedTranscodeLimit < 1 || mediaStreamLimit < 1) {
            throw new IllegalArgumentException("Resource budget limits must be at least one");
        }
        this.idleProbeLimit = idleProbeLimit;
        this.playingProbeLimit = playingProbeLimit;
        this.liveTranscodeLimit = liveTranscodeLimit;
        this.managedTranscodeLimit = managedTranscodeLimit;
        this.mediaStreamLimit = mediaStreamLimit;
        this.playbackBusy = Objects.requireNonNull(playbackBusy, "playbackBusy");
    }

    /** Waits outside database transactions until one probe slot is available. */
    public Permit acquireProbe() throws InterruptedException {
        lock.lockInterruptibly();
        boolean registeredWaiter = false;
        long waitStartedNanos = 0;
        try {
            while (activeProbes >= currentProbeLimit()) {
                if (!registeredWaiter) {
                    waitingProbes++;
                    waitStartedNanos = System.nanoTime();
                    registeredWaiter = true;
                }
                capacityChanged.awaitNanos(PRESSURE_RECHECK_NANOS);
            }
            activeProbes++;
            return new Permit(this, Work.PROBE);
        } finally {
            if (registeredWaiter) {
                waitingProbes--;
                probeWaitStats.record(Math.max(0L, System.nanoTime() - waitStartedNanos));
            }
            lock.unlock();
        }
    }

    /** Reserves a live transcode slot before returning an HTTP success response. */
    public Optional<Permit> tryAcquireLiveTranscode() {
        lock.lock();
        try {
            if (activeLiveTranscodes >= liveTranscodeLimit) return Optional.empty();
            activeLiveTranscodes++;
            capacityChanged.signalAll();
            return Optional.of(new Permit(this, Work.LIVE_TRANSCODE));
        } finally {
            lock.unlock();
        }
    }

    /** Admits a media response only when a bounded stream slot is available. */
    public Optional<Permit> tryAcquireMediaStream() {
        lock.lock();
        try {
            if (activeMediaStreams >= mediaStreamLimit) return Optional.empty();
            activeMediaStreams++;
            return Optional.of(new Permit(this, Work.MEDIA_STREAM));
        } finally {
            lock.unlock();
        }
    }

    /** Waits outside database transactions until a Managed encoding slot is available. */
    public Permit acquireManagedTranscode() throws InterruptedException {
        lock.lockInterruptibly();
        boolean registeredWaiter = false;
        long waitStartedNanos = 0;
        try {
            while (activeManagedTranscodes >= managedTranscodeLimit) {
                if (!registeredWaiter) {
                    waitingManagedTranscodes++;
                    waitStartedNanos = System.nanoTime();
                    registeredWaiter = true;
                }
                capacityChanged.awaitNanos(PRESSURE_RECHECK_NANOS);
            }
            activeManagedTranscodes++;
            capacityChanged.signalAll();
            return new Permit(this, Work.MANAGED_TRANSCODE);
        } finally {
            if (registeredWaiter) {
                waitingManagedTranscodes--;
                managedTranscodeWaitStats.record(Math.max(0L, System.nanoTime() - waitStartedNanos));
            }
            lock.unlock();
        }
    }

    /** Returns a consistent, secret-free snapshot of current admission pressure and completed waits. */
    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(
                    new WaitSnapshot(activeProbes, waitingProbes, currentProbeLimit(),
                            probeWaitStats.count, probeWaitStats.totalNanos, probeWaitStats.maxNanos),
                    activeLiveTranscodes,
                    liveTranscodeLimit,
                    new WaitSnapshot(activeManagedTranscodes, waitingManagedTranscodes,
                            managedTranscodeLimit, managedTranscodeWaitStats.count,
                            managedTranscodeWaitStats.totalNanos, managedTranscodeWaitStats.maxNanos));
        } finally {
            lock.unlock();
        }
    }

    public int activeProbeCount() {
        lock.lock();
        try {
            return activeProbes;
        } finally {
            lock.unlock();
        }
    }

    public int activeLiveTranscodeCount() {
        lock.lock();
        try {
            return activeLiveTranscodes;
        } finally {
            lock.unlock();
        }
    }

    public int activeManagedTranscodeCount() {
        lock.lock();
        try {
            return activeManagedTranscodes;
        } finally {
            lock.unlock();
        }
    }

    public int activeMediaStreamCount() {
        lock.lock();
        try {
            return activeMediaStreams;
        } finally {
            lock.unlock();
        }
    }

    /** Wakes probe waiters immediately when the sampled playback state changes. */
    public void signalWaiters() {
        lock.lock();
        try {
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private int currentProbeLimit() {
        return playbackBusy.getAsBoolean() || activeLiveTranscodes > 0 || activeManagedTranscodes > 0
                ? playingProbeLimit
                : idleProbeLimit;
    }

    private void release(Work work) {
        lock.lock();
        try {
            if (work == Work.PROBE) {
                if (activeProbes <= 0) throw new IllegalStateException("Probe permit count underflow");
                activeProbes--;
            } else if (work == Work.LIVE_TRANSCODE) {
                if (activeLiveTranscodes <= 0) throw new IllegalStateException("Transcode permit count underflow");
                activeLiveTranscodes--;
            } else if (work == Work.MANAGED_TRANSCODE) {
                if (activeManagedTranscodes <= 0) throw new IllegalStateException("Managed transcode permit count underflow");
                activeManagedTranscodes--;
            } else {
                if (activeMediaStreams <= 0) throw new IllegalStateException("Media stream permit count underflow");
                activeMediaStreams--;
            }
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private enum Work { PROBE, LIVE_TRANSCODE, MANAGED_TRANSCODE, MEDIA_STREAM }

    public record WaitSnapshot(
            int active,
            int waiting,
            int limit,
            long waitCount,
            long totalWaitNanos,
            long maxWaitNanos) { }

    public record Snapshot(
            WaitSnapshot probes,
            int activeLiveTranscodes,
            int liveTranscodeLimit,
            WaitSnapshot managedTranscodes) { }

    private static final class WaitStats {
        private long count;
        private long totalNanos;
        private long maxNanos;

        private void record(long durationNanos) {
            if (count < Long.MAX_VALUE) count++;
            totalNanos = totalNanos > Long.MAX_VALUE - durationNanos
                    ? Long.MAX_VALUE
                    : totalNanos + durationNanos;
            maxNanos = Math.max(maxNanos, durationNanos);
        }
    }

    /** Idempotent permit; the owner controls its lifetime, including confirmed process exit. */
    public static final class Permit implements AutoCloseable {
        private final ResourceBudget owner;
        private final Work work;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(ResourceBudget owner, Work work) {
            this.owner = owner;
            this.work = work;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) owner.release(work);
        }
    }
}
