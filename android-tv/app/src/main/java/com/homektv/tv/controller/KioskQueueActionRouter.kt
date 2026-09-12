package com.homektv.tv.controller

import com.homektv.tv.net.SongDto

/** Routes TV-kiosk queue mutations through the same controller state machine as phone/tablet UI. */
class KioskQueueActionRouter(
    private val actions: ControllerActions,
) {
    fun order(song: SongDto, onComplete: (Boolean) -> Unit) = actions.order(song, onComplete)

    fun orderTop(song: SongDto, onComplete: (Boolean) -> Unit) = actions.orderTop(song, onComplete)

    fun top(queueId: Long) = actions.top(queueId)

    fun cancel(queueId: Long) = actions.cancel(queueId)

    fun shuffle() = actions.control("shuffle")
}
