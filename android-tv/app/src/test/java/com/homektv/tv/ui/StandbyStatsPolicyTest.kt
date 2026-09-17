package com.homektv.tv.ui

import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.QueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class StandbyStatsPolicyTest {

    @Test
    fun countsOnlyWaitingEntriesFromTheSnapshot() {
        val snapshot = QueueSnapshot(
            list = listOf(
                QueueEntry(status = "waiting"),
                QueueEntry(status = "playing"),
                QueueEntry(status = "waiting"),
            ),
        )

        assertEquals(2, StandbyStatsPolicy.waitingCount(snapshot))
    }
}
