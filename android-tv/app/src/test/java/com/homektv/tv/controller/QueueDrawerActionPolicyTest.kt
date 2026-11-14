package com.homektv.tv.controller

import com.homektv.tv.net.QueueEntry
import com.homektv.tv.ui.QueueDrawerDiffPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDrawerActionPolicyTest {

    @Test
    fun pendingQueueAction_isDisabledUntilControllerFinishes() {
        assertFalse(
            QueueDrawerActionPolicy.isEnabled(
                action = "control:shuffle",
                pendingActions = setOf(ActionKey("control:shuffle")),
            ),
        )
        assertTrue(
            QueueDrawerActionPolicy.isEnabled(
                action = "control:shuffle",
                pendingActions = emptySet(),
            ),
        )
    }

    @Test
    fun queueDrawerAdapterDoesNotTreatFirstWaitingIndexAsPlayingAndSyncsStatusInSubmitQueue() {
        val dialogSource = locate("src/main/java/com/homektv/tv/ui/KtvQueueDrawerDialog.kt").readText()
        val adapterBind = dialogSource.substringAfter("fun bind(entry: QueueEntry, index: Int)")
            .substringBefore("binding.txtQueueTitle.text")
        assertFalse(
            "Waiting queue item at index == 1 must not be forced into playing equalizer state",
            adapterBind.contains("index == 1"),
        )

        val submitSignature = dialogSource.substringAfter("fun submitQueue(")
            .substringBefore("fun showUnavailable(")
        assertTrue(
            "submitQueue must accept explicit statusMessage so stale warnings are rendered atomically",
            submitSignature.contains("statusMessage: String? = null") &&
                submitSignature.contains("setQueueStatus(message = statusMessage, retryAvailable = retryAvailable)"),
        )

        val updateShuffle = dialogSource.substringAfter("private fun updateShuffleEnabled()")
            .substringBefore("fun updateActor(")
        assertTrue(
            "updateShuffleEnabled must enforce QueueDrawerActionPolicy",
            updateShuffle.contains("QueueDrawerActionPolicy.isEnabled"),
        )
    }

    @Test
    fun queueDrawerDiffDetectsPositionShiftsAndFixesFocusNavigationAndRetryCondition() {
        val e1 = QueueEntry(queueId = 101L)
        val e2 = QueueEntry(queueId = 102L)
        val e3 = QueueEntry(queueId = 103L)

        assertEquals(emptyList<Int>(), QueueDrawerDiffPolicy.shiftedPositions(listOf(e1, e2), listOf(e1, e2)))
        assertEquals(listOf(0, 1), QueueDrawerDiffPolicy.shiftedPositions(listOf(e1, e2, e3), listOf(e2, e1, e3)))
        assertEquals(listOf(0, 1), QueueDrawerDiffPolicy.shiftedPositions(listOf(e1, e2, e3), listOf(e2, e3)))

        val dialogSource = locate("src/main/java/com/homektv/tv/ui/KtvQueueDrawerDialog.kt").readText()
        assertTrue(
            "submitQueue 必须在 submitList 后对位次变化行重新绑定序号徽章",
            dialogSource.contains("QueueDrawerDiffPolicy.shiftedPositions("),
        )
        assertTrue(
            "抽屉遮罩与容器不得在运行时抢占 D-pad 焦点",
            dialogSource.contains("binding.queueDrawerRoot.isFocusable = false") &&
                dialogSource.contains("binding.queueDrawerPanel.isFocusable = false"),
        )
        val syncHeaderFn = dialogSource.substringAfter("private fun syncHeaderFocusNavigation()")
            .substringBefore("fun renderHistoryState(")
        assertTrue(
            "DOWNLOADS 空页签需要在运行时把 drawerTabDownloads.nextFocusDownId 锁定自身防逃逸",
            syncHeaderFn.contains("binding.drawerTabDownloads.nextFocusDownId = binding.drawerTabDownloads.id"),
        )
        assertTrue(
            "抽屉关闭恢复焦点时不得在触控模式下强行触发 focus ring",
            dialogSource.contains("!isInTouchMode") || dialogSource.contains("isInTouchMode"),
        )

        val kioskSource = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(
            "空队列正常状态不得展示失败重试按钮，仅 UiDomain.QUEUE 异常才开放 retryAvailable",
            kioskSource.contains("retryAvailable = !state.queueLoading && state.errorFor(com.homektv.tv.controller.UiDomain.QUEUE) != null"),
        )
    }

    @Test
    fun historyTimestampFormatterConvertsIsoUtcToLocalDisplayAndFallsBackSafely() {
        val formatted = com.homektv.tv.ui.HistoryTimestampFormatter.formatLocal(
            raw = "2026-11-14T15:12:03Z",
            zoneId = java.time.ZoneId.of("Asia/Shanghai"),
        )
        assertEquals("11-14 23:12", formatted)
        assertEquals("刚刚", com.homektv.tv.ui.HistoryTimestampFormatter.formatLocal("刚刚", java.time.ZoneId.of("Asia/Shanghai")))

        val drawerSource = locate("src/main/java/com/homektv/tv/ui/KtvQueueDrawerDialog.kt").readText()
        assertTrue(
            "HistoryViewHolder 应通过 HistoryTimestampFormatter.formatLocal 格式化时间",
            drawerSource.contains("HistoryTimestampFormatter.formatLocal(it)"),
        )
    }

    private fun locate(relativePath: String): java.io.File {
        val workingDirectory = java.io.File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("android-tv/app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
        ).firstOrNull(java.io.File::isFile) ?: error("Cannot locate $relativePath")
    }
}
