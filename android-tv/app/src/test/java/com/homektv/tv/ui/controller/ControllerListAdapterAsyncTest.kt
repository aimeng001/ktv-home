package com.homektv.tv.ui.controller

import com.homektv.tv.net.ArtistItem
import com.homektv.tv.net.NamedCount
import com.homektv.tv.net.PlaylistSummary
import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.RecentHistoryItem
import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ControllerListAdapterAsyncTest {

    @Test
    fun panelRowViewTypeMapsAllEightRowTypesDeterministically() {
        val songRow = SongPanelRow(
            song = SongDto(id = 1L, title = "A", artist = "B"),
            orderPending = false,
            favorite = false,
            favoritePending = false,
        )
        val artistRow = ArtistPanelRow(
            artist = ArtistItem(artistKey = "key", name = "Name", gender = "男", songCount = 10),
            action = {},
        )
        val namedCountRow = NamedCountPanelRow(
            item = NamedCount(name = "国语", songCount = 5),
            action = {},
        )
        val playlistRow = PlaylistPanelRow(
            playlist = PlaylistSummary(id = 10L, name = "精选", songCount = 20),
            orderPending = false,
        )
        val historyRow = HistoryPanelRow(
            item = RecentHistoryItem(historyId = 100L, song = SongDto(id = 2L, title = "C", artist = "D")),
            repeatPending = false,
        )
        val queueRow = QueuePanelRow(
            index = 1,
            entry = QueueEntry(queueId = 50L, song = SongDto(id = 3L, title = "E", artist = "F")),
            topPending = false,
            cancelPending = false,
            canManage = true,
        )
        val messageRow = MessagePanelRow("没有找到相关歌曲")
        val actionRow = ActionPanelRow(
            id = -1L,
            text = "加载更多",
            enabled = true,
            contentDescription = "加载更多",
            action = {},
        )

        assertEquals(PanelRowViewType.SONG, PanelRowViewType.from(songRow))
        assertEquals(PanelRowViewType.ARTIST, PanelRowViewType.from(artistRow))
        assertEquals(PanelRowViewType.NAMED_COUNT, PanelRowViewType.from(namedCountRow))
        assertEquals(PanelRowViewType.PLAYLIST, PanelRowViewType.from(playlistRow))
        assertEquals(PanelRowViewType.HISTORY, PanelRowViewType.from(historyRow))
        assertEquals(PanelRowViewType.QUEUE, PanelRowViewType.from(queueRow))
        assertEquals(PanelRowViewType.MESSAGE, PanelRowViewType.from(messageRow))
        assertEquals(PanelRowViewType.ACTION, PanelRowViewType.from(actionRow))
    }

    @Test
    fun adapterReportsCorrectViewTypesAndStableIds() {
        val songRow = SongPanelRow(
            song = SongDto(id = 88L, title = "Song", artist = "Artist"),
            orderPending = false,
            favorite = false,
            favoritePending = false,
        )
        val messageRow = MessagePanelRow(id = -99L, text = "Empty")
        val syncExecutor = Executor { it.run() }
        val adapter = ControllerListAdapter<PanelRow>(
            itemId = { it.stableId },
            createView = { _, _ -> error("Should not be called in this test") },
            bindView = { _, _, _ -> },
            viewType = PanelRowViewType::from,
            backgroundExecutor = syncExecutor,
            mainThreadExecutor = syncExecutor,
        )

        adapter.submitList(listOf(songRow, messageRow))

        assertEquals(2, adapter.itemCount)
        assertEquals(88L, adapter.getItemId(0))
        assertEquals(-99L, adapter.getItemId(1))
        assertEquals(PanelRowViewType.SONG, adapter.getItemViewType(0))
        assertEquals(PanelRowViewType.MESSAGE, adapter.getItemViewType(1))
    }

    @Test
    fun adapterAsyncDiffComputationExecutesOnConfiguredExecutorAndNotifiesCallback() {
        val bgExecutor = Executors.newSingleThreadExecutor()
        val syncExecutor = Executor { it.run() }

        val adapter = ControllerListAdapter<Long>(
            itemId = { it },
            createView = { _, _ -> error("Not used") },
            bindView = { _, _, _ -> },
            backgroundExecutor = bgExecutor,
            mainThreadExecutor = syncExecutor,
        )

        val latch1 = CountDownLatch(1)
        adapter.submitList((1L..50L).toList()) {
            latch1.countDown()
        }

        val completed1 = latch1.await(2, TimeUnit.SECONDS)
        assertTrue("Initial submit should complete", completed1)
        assertEquals(50, adapter.itemCount)

        // Now submit an updated list that modifies middle items to test diff calculation
        val latch2 = CountDownLatch(1)
        val updatedList = (1L..25L).toList() + listOf(999L) + (27L..50L).toList()
        adapter.submitList(updatedList) {
            latch2.countDown()
        }

        val completed2 = latch2.await(2, TimeUnit.SECONDS)
        assertTrue("Diff calculation should complete in background and trigger callback", completed2)
        assertEquals(50, adapter.itemCount)
        assertEquals(999L, adapter.getItemId(25))
        bgExecutor.shutdown()
    }

    @Test
    fun removalPolicy_shouldNotifyRemoval_onlyWhenCountPositive() {
        org.junit.Assert.assertFalse(ControllerListRemovalPolicy.shouldNotifyRemoval(0))
        org.junit.Assert.assertFalse(ControllerListRemovalPolicy.shouldNotifyRemoval(-1))
        org.junit.Assert.assertTrue(ControllerListRemovalPolicy.shouldNotifyRemoval(5))
    }
}
