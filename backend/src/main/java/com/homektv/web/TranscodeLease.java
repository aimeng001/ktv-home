package com.homektv.web;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** One semaphore permit held from HTTP admission until the stream body completes or is abandoned. */
final class TranscodeLease implements AutoCloseable {
    private static final int RESERVED = 0;
    private static final int STREAMING = 1;
    private static final int RELEASED = 2;

    private final Semaphore semaphore;
    private final AtomicInteger state = new AtomicInteger(RESERVED);

    TranscodeLease(Semaphore semaphore) {
        this.semaphore = semaphore;
    }

    boolean beginStreaming() {
        return state.compareAndSet(RESERVED, STREAMING) || state.get() == STREAMING;
    }

    void releaseIfNotStarted() {
        if (state.compareAndSet(RESERVED, RELEASED)) semaphore.release();
    }

    @Override
    public void close() {
        int previous = state.getAndSet(RELEASED);
        if (previous != RELEASED) semaphore.release();
    }
}
