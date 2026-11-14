package com.homektv.tv.controller

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
