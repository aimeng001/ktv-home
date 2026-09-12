package com.homektv.tv.controller

import com.homektv.tv.net.QueueEntry

/** UI visibility mirrors the server's owner-or-host rule using stable ids. */
object QueuePermissionPolicy {
    fun canManage(entry: QueueEntry, currentUserId: Long?, isRoomHost: Boolean): Boolean =
        isRoomHost || (currentUserId != null && entry.orderedBy != null && entry.orderedBy == currentUserId)

    fun canManage(entry: QueueEntry, state: ControllerUiState): Boolean =
        canManage(
            entry = entry,
            currentUserId = state.currentUser?.id,
            isRoomHost = state.roomHost.isHost,
        )
}
