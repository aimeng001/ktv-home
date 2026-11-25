package com.homektv.musicsource;

/** Bounds the number of metadata jobs held by one worker claim. */
public final class MetadataScrapeBatchPolicy {
    public static final int MAX_INFLIGHT_CLAIM_SIZE = 10;

    private MetadataScrapeBatchPolicy() {}

    public static int safeInflightClaimSize(int concurrencyLimit) {
        return Math.max(1, Math.min(Math.max(1, concurrencyLimit) * 2, MAX_INFLIGHT_CLAIM_SIZE));
    }
}
