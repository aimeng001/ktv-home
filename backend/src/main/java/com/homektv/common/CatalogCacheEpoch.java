package com.homektv.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local monotonic mutation epoch shared across JPA entity hooks and
 * bulk catalog revision bumps so short-lived search caches stay coherent
 * without polling the database on every cache hit.
 */
public final class CatalogCacheEpoch {

    private static final AtomicLong EPOCH = new AtomicLong(1L);

    private CatalogCacheEpoch() {}

    public static long current() {
        return EPOCH.get();
    }

    public static long bump() {
        return EPOCH.incrementAndGet();
    }
}
