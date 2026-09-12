package com.homektv.tv.controller

import com.homektv.tv.net.SongDto
import kotlinx.coroutines.flow.StateFlow

/** UI-facing controller operations shared by phone, tablet, and TV kiosk. */
interface ControllerActions {
    val state: StateFlow<ControllerUiState>

    fun setQuery(value: String)
    fun loadMoreSearch()
    fun order(song: SongDto) {
        order(song) { }
    }

    fun order(song: SongDto, onComplete: (Boolean) -> Unit)
    fun orderTop(song: SongDto, onComplete: (Boolean) -> Unit)
    fun top(queueId: Long)
    fun cancel(queueId: Long)
    fun control(action: String, params: Map<String, Any?> = emptyMap())
    fun refreshQueue()
    fun loadRoomHost()

}
