package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingFinishedPolicyTest {
    @Test
    fun durableQueueUsesTheSameBoundForRuntimeAndPersistence() {
        assertEquals(FinishedReportOutbox.MAX_PENDING, PendingFinishedPolicy.MAX_PENDING)
        assertEquals(1_000, PendingFinishedPolicy.sanitize((1L..1_200L).toList()).size)
    }

    @Test
    fun legacyAndTargetPendingIdsAreMergedWithoutDuplicates() {
        assertEquals(
            listOf(7L, 8L, 9L),
            PendingFinishedPolicy.merge(primary = listOf(7L, 8L), secondary = listOf(8L, 9L)),
        )
    }
}
