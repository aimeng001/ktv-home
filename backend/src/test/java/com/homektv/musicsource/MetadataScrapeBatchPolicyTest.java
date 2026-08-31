package com.homektv.musicsource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataScrapeBatchPolicyTest {
    @Test
    void capsEveryWorkerClaimToTheConfiguredSafeMaximum() {
        assertThat(MetadataScrapeBatchPolicy.safeBatchSize(0)).isEqualTo(1);
        assertThat(MetadataScrapeBatchPolicy.safeBatchSize(100)).isEqualTo(100);
        assertThat(MetadataScrapeBatchPolicy.safeBatchSize(10_000)).isEqualTo(500);
    }
}
