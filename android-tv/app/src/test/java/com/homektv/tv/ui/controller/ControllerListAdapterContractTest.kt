package com.homektv.tv.ui.controller

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure contract for the identity data used by the RecyclerView adapter. */
class ControllerListAdapterContractTest {
    @Test
    fun stableIdsRemainAddressableAfterAppendingSecondPage() {
        val firstPage = listOf(1L, 2L, 3L)
        val all = firstPage + (4L..100L)

        assertEquals(100, all.size)
        assertEquals(51L, all[50])
        assertEquals(firstPage.toSet(), all.take(3).toSet())
    }
}
