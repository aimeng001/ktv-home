package com.homektv.tv.ui

import com.homektv.tv.controller.QueuePermissionPolicy
import com.homektv.tv.net.QueueSnapshot

sealed interface SongQueueState {
    data object Playing : SongQueueState
    data class Waiting(
        val position: Int,
        val queueId: Long = 0L,
        val orderedBy: Long? = null,
        val canManage: Boolean = true,
    ) : SongQueueState
}

object SongQueueProjection {
    fun from(
        snapshot: QueueSnapshot,
        currentUserId: Long? = null,
        isRoomHost: Boolean = false,
    ): Map<Long, SongQueueState> {
        val result = linkedMapOf<Long, SongQueueState>()
        snapshot.playing?.song?.id?.let { result[it] = SongQueueState.Playing }
        snapshot.list.filter { it.status == "waiting" }.forEachIndexed { idx, entry ->
            val songId = entry.song?.id ?: return@forEachIndexed
            val queueId = entry.queueId ?: return@forEachIndexed
            // Unknown actor is never an authorization signal. The server remains
            // the final authority; this projection only controls local affordances.
            val canManage = QueuePermissionPolicy.canManage(entry, currentUserId, isRoomHost)
            result.putIfAbsent(songId, SongQueueState.Waiting(
                position = idx + 1,
                queueId = queueId,
                orderedBy = entry.orderedBy,
                canManage = canManage,
            ))
        }
        return result
    }
}
