package com.homektv.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MissingFileGuardTest {

    private final MissingFileGuard guard = new MissingFileGuard(1_000, 0.50d);

    @Test
    void blocksAnEmptyObservationForAPreviouslyLargeLibrary() {
        assertThat(guard.shouldBlock(202_434, 0, true, false)).isTrue();
    }

    @Test
    void allowsACompleteObservationThatRemainsAboveTheConfiguredRatio() {
        assertThat(guard.shouldBlock(202_434, 180_000, true, false)).isFalse();
    }

    @Test
    void doesNotApplyTheMassMissingGuardToSmallLibraries() {
        assertThat(guard.shouldBlock(500, 0, true, false)).isFalse();
    }

    @Test
    void incompleteEnumerationIsAlwaysBlocked() {
        assertThat(guard.shouldBlock(10, 10, false, false)).isTrue();
    }

    @Test
    void explicitAdminOverrideAllowsAConfirmedBulkReconciliation() {
        assertThat(guard.shouldBlock(202_434, 0, true, true)).isFalse();
    }
}
