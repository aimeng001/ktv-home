package com.homektv.tv.ui

import com.homektv.tv.net.QueueSnapshot

/** Computes only statistics that are present in the authoritative queue snapshot. */
internal object StandbyStatsPolicy {
    fun waitingCount(snapshot: QueueSnapshot): Int =
        snapshot.list.count { it.status == "waiting" }
}
