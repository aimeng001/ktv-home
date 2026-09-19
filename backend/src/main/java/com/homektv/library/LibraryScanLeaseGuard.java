package com.homektv.library;

/** Ownership check injected into database-writing scan batches. */
@FunctionalInterface
public interface LibraryScanLeaseGuard {
    void assertOwned();

    static LibraryScanLeaseGuard noop() {
        return () -> {};
    }
}

