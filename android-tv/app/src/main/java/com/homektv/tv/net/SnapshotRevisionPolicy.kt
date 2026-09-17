package com.homektv.tv.net

/** Rejects legacy/older snapshots after a revisioned snapshot has been applied. */
internal object SnapshotRevisionPolicy {
    fun accepts(currentRevision: Long, incomingRevision: Long): Boolean =
        currentRevision <= 0L ||
            (incomingRevision > 0L && incomingRevision >= currentRevision)
}
