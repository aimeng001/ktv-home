package com.homektv.tv.controller

import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class KioskQueueActionRouterTest {

    @Test
    fun routesQueueMutationsThroughControllerActions() {
        val fake = RecordingControllerActions()
        val router = KioskQueueActionRouter(fake)
        val song = SongDto(id = 13L, title = "晴天", artist = "周杰伦")

        router.order(song) { success -> fake.calls += "order-result:$success" }
        router.orderTop(song) { success -> fake.calls += "order-top-result:$success" }
        router.top(11L)
        router.cancel(12L)
        router.shuffle()

        assertEquals(
            listOf(
                "order:13",
                "order-result:true",
                "order-top:13",
                "order-top-result:true",
                "top:11",
                "cancel:12",
                "control:shuffle",
            ),
            fake.calls,
        )
    }

    private class RecordingControllerActions : ControllerActions {
        override val state = MutableStateFlow(ControllerUiState())
        val calls = mutableListOf<String>()

        override fun setQuery(value: String) = Unit
        override fun loadMoreSearch() = Unit
        override fun order(song: SongDto) {
            calls += "order:${song.id}"
        }
        override fun order(song: SongDto, onComplete: (Boolean) -> Unit) {
            calls += "order:${song.id}"
            onComplete(true)
        }
        override fun orderTop(song: SongDto, onComplete: (Boolean) -> Unit) {
            calls += "order-top:${song.id}"
            onComplete(true)
        }
        override fun top(queueId: Long) {
            calls += "top:$queueId"
        }
        override fun cancel(queueId: Long) {
            calls += "cancel:$queueId"
        }
        override fun control(action: String, params: Map<String, Any?>) {
            calls += "control:$action"
        }
        override fun refreshQueue() = Unit
        override fun loadRoomHost() = Unit
    }
}
