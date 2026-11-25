package com.homektv.musicsource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataScrapeBatchPolicyTest {
    @Test
    void capsEveryWorkerClaimToTheConfiguredSafeMaximum() {
        assertThat(MetadataScrapeBatchPolicy.safeInflightClaimSize(1)).isEqualTo(2);
        assertThat(MetadataScrapeBatchPolicy.safeInflightClaimSize(4)).isEqualTo(8);
        assertThat(MetadataScrapeBatchPolicy.safeInflightClaimSize(10)).isEqualTo(10);
    }
}
