package com.homektv.library;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Owns a persistent scan lease and refreshes it while a long-running scan is
 * doing filesystem/FFprobe work.
 */
public final class LibraryScanLease implements AutoCloseable {
    private final LibraryScanStateStore.Claim claim;
    private final LibraryScanStateStore stateStore;
    private final Supplier<LibraryScanStateStore.ScanSnapshot> progress;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final ScheduledFuture<?> heartbeatTask;

    public LibraryScanLease(LibraryScanStateStore.Claim claim,
                            LibraryScanStateStore stateStore,
                            Supplier<LibraryScanStateStore.ScanSnapshot> progress,
                            ScheduledExecutorService scheduler,
                            Duration interval) {
        this.claim = Objects.requireNonNull(claim);
        this.stateStore = Objects.requireNonNull(stateStore);
        this.progress = Objects.requireNonNull(progress);
        Duration safeInterval = interval == null || interval.isZero() || interval.isNegative()
                ? Duration.ofSeconds(30) : interval;
        this.heartbeatTask = scheduler.scheduleAtFixedRate(
                this::refresh,
                safeInterval.toMillis(),
                safeInterval.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void assertOwned() {
        if (lost.get() || !refresh()) {
            throw new LeaseLostException("扫描租约已失效：" + claim.scanId());
        }
    }

    public boolean isLost() {
        return lost.get();
    }

    private boolean refresh() {
        if (lost.get()) return false;
        try {
            boolean owned = stateStore.heartbeat(claim, progress.get());
            if (!owned) lost.set(true);
            return owned;
        } catch (RuntimeException failure) {
            lost.set(true);
            return false;
        }
    }

    @Override
    public void close() {
        heartbeatTask.cancel(false);
    }
}

