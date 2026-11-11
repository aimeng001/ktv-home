package com.homektv.tv.ui

import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvMarqueeNotificationControllerTest {
    @Test
    fun incomingOrderEventFormatsProperNotificationText() {
        val controller = KtvMarqueeNotificationController()

        assertEquals(
            "来自 手机用户 点播了《海阔天空》",
            controller.formatOrderNotification("手机用户", "海阔天空"),
        )
    }

    @Test
    fun higherPriorityTopNotificationIsDeliveredBeforeEarlierOrder() {
        val controller = KtvMarqueeNotificationController()
        assertTrue(controller.enqueueOrder(11, "小王", "海阔天空"))
        assertTrue(controller.enqueueTop(12, "阿辉", "富士山下", revision = 8))

        assertEquals("来自 阿辉 置顶了《富士山下》", controller.pollNext()?.text)
        assertEquals("来自 小王 点播了《海阔天空》", controller.pollNext()?.text)
        assertEquals(null, controller.pollNext())
    }

    @Test
    fun queueUpdateAnnouncesOnlyNewSongsAndDeduplicatesRepeatedSnapshots() {
        val controller = KtvMarqueeNotificationController()
        val previous = snapshot(entry(1, "小王", "旧歌"))
        val current = snapshot(entry(1, "小王", "旧歌"), entry(2, "小李", "新歌"))

        assertEquals(0, controller.enqueueSnapshotChange("queue_updated", null, current))
        assertEquals(1, controller.enqueueSnapshotChange("queue_updated", previous, current))
        assertEquals(0, controller.enqueueSnapshotChange("queue_updated", previous, current))
        assertEquals("来自 小李 点播了《新歌》", controller.pollNext()?.text)
    }

    @Test
    fun queueUpdateRecognizesAQueueItemMovedToTheFrontAsTopSong() {
        val controller = KtvMarqueeNotificationController()
        val previous = snapshot(
            entry(1, "小王", "第一首"),
            entry(2, "阿辉", "置顶歌曲"),
            entry(3, "小李", "第三首"),
        )
        val current = snapshot(
            entry(2, "阿辉", "置顶歌曲"),
            entry(1, "小王", "第一首"),
            entry(3, "小李", "第三首"),
        )

        assertEquals(1, controller.enqueueSnapshotChange("queue_updated", previous, current))
        assertEquals("来自 阿辉 置顶了《置顶歌曲》", controller.pollNext()?.text)
    }

    @Test
    fun explicitOrderAndTopEventsUseSnapshotEntriesAndTopHasHigherPriority() {
        val controller = KtvMarqueeNotificationController()
        val previous = snapshot(entry(1, "小王", "旧歌"))
        val afterOrder = snapshot(entry(1, "小王", "旧歌"), entry(2, "阿辉", "新歌"))
        val afterTop = snapshot(entry(2, "阿辉", "新歌"), entry(1, "小王", "旧歌"))

        assertEquals(1, controller.enqueueSnapshotChange("order_song", previous, afterOrder))
        assertEquals(1, controller.enqueueSnapshotChange("top_song", afterOrder, afterTop))
        assertEquals("来自 阿辉 置顶了《新歌》", controller.pollNext()?.text)
        assertEquals("来自 阿辉 点播了《新歌》", controller.pollNext()?.text)
    }

    @Test
    fun ordinaryQueueReorderingDoesNotPretendToBeATopAction() {
        val controller = KtvMarqueeNotificationController()
        val previous = snapshot(
            entry(1, "小王", "第一首"),
            entry(2, "阿辉", "第二首"),
            entry(3, "小李", "第三首"),
        )
        val current = snapshot(
            entry(3, "小李", "第三首"),
            entry(2, "阿辉", "第二首"),
            entry(1, "小王", "第一首"),
        )

        assertEquals(0, controller.enqueueSnapshotChange("queue_updated", previous, current))
        assertFalse(controller.hasPendingNotifications())
    }

    private fun entry(id: Long, nick: String, title: String) = QueueEntry(
        queueId = id,
        song = SongDto(id = id, title = title),
        orderedByNick = nick,
    )

    private fun snapshot(vararg entries: QueueEntry) = QueueSnapshot(list = entries.toList())

    @Test
    fun formatsAndEnqueuesNextSongNotification() {
        val controller = KtvMarqueeNotificationController()
        val playingEntry = NowPlaying(
            queueId = 99,
            song = SongDto(id = 88, title = "晴天", artist = "周杰伦"),
            orderedByNick = "杰迷",
        )
        val snapshot = QueueSnapshot(
            playing = playingEntry,
            list = emptyList(),
            stateRevision = 12L,
        )

        assertEquals(1, controller.enqueueSnapshotChange("next_song", null, snapshot))
        val notification = controller.pollNext()
        assertEquals(
            "接下来播放：《晴天》- 周杰伦（点播人：杰迷）",
            notification?.text,
        )
    }

    @Test
    fun marqueeTextViewOverridesFocusToKeepRunningOnTv() {
        val workingDirectory = java.io.File(System.getProperty("user.dir").orEmpty())
        val sourceFile = sequenceOf(
            workingDirectory.resolve("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt"),
            workingDirectory.resolve("app/src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt"),
            workingDirectory.resolve("android-tv/app/src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt"),
        ).firstOrNull(java.io.File::isFile) ?: error("Cannot locate KtvBottomDockController.kt")

        val source = sourceFile.readText()
        val marqueeCode = source.substringAfter("class MarqueeTextView")
        assertTrue("MarqueeTextView 必须始终覆盖报告 isFocused=true", marqueeCode.contains("override fun isFocused(): Boolean = true"))
        assertTrue("MarqueeTextView 必须在窗口焦点变动时保持滚动", marqueeCode.contains("override fun onWindowFocusChanged("))
    }
}
