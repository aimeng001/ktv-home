package com.homektv.tv.net

/** Protocol events that prove the WebSocket is usable before a full sync arrives. */
internal object ConnectionEventPolicy {
    fun establishesConnection(event: String): Boolean =
        event == "player_role" || SnapshotEventPolicy.isCompleteSnapshot(event)
}
