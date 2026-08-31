package com.homektv.musicsource;

/** Bounds the number of metadata jobs held by one worker claim. */
public final class MetadataScrapeBatchPolicy {
    public static final int MAX_CLAIM_SIZE = 500;

    private MetadataScrapeBatchPolicy() {}

    public static int safeBatchSize(int requested) {
        return Math.max(1, Math.min(requested, MAX_CLAIM_SIZE));
    }
}
