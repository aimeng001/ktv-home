package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityModalBackDispatchContractTest {
    @Test
    fun activeDialogBackIsConsumedBeforeKioskRouteHandling() {
        val source = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val dispatch = source.substringAfter("override fun dispatchKeyEvent(event: KeyEvent): Boolean {")
            .substringBefore("\n    override fun")
        val modalDispatchIndex = dispatch.indexOf("consumeActiveModalDialogBack(event)")
        val kioskDispatchIndex = dispatch.indexOf("kioskController.dispatchKeyEvent(event)")

        assertTrue(modalDispatchIndex >= 0)
        assertTrue(kioskDispatchIndex > modalDispatchIndex)

        val modalHandler = source.substringAfter("private fun consumeActiveModalDialogBack(event: KeyEvent): Boolean {")
            .substringBefore("\n    }")
        assertTrue(modalHandler.contains("activeModalDialogs.lastOrNull { it.isShowing }"))
        assertTrue(modalHandler.contains("activeDialog.dismiss()"))
    }

    @Test
    fun modalInitialFocusIsRequestedAfterWindowShowAndSupportsTouchMode() {
        val qrDialog = locate("src/main/java/com/homektv/tv/ui/KtvQrDialog.kt").readText()
        val capabilityDialog = locate("src/main/java/com/homektv/tv/ui/KtvCapabilityDrawerDialog.kt").readText()
        val queueDialog = locate("src/main/java/com/homektv/tv/ui/KtvQueueDrawerDialog.kt").readText()
        val focusHelper = locate("src/main/java/com/homektv/tv/ui/KtvFocusController.kt").readText()

        assertTrue(qrDialog.contains("binding.btnQrClose.post { binding.btnQrClose.requestRemoteFocus() }"))
        assertTrue(capabilityDialog.contains("binding.btnCapabilityClose.post { binding.btnCapabilityClose.requestRemoteFocus() }"))
        assertTrue(queueDialog.contains("binding.btnDrawerClose.post {"))
        assertTrue(queueDialog.contains("suspendBackgroundFocus"))
        assertTrue(queueDialog.contains("binding.btnDrawerClose.requestRemoteFocus()"))
        assertTrue(queueDialog.contains("restoreBackgroundFocus()"))
        assertTrue(focusHelper.contains("if (isInTouchMode) requestFocusFromTouch() else requestFocus()"))
        assertFalse(qrDialog.contains("binding.btnQrClose.requestFocus()"))
    }

    private fun locate(relativePath: String): File {
        var current = File(System.getProperty("user.dir").orEmpty())
        repeat(6) {
            val candidate = File(current, "android-tv/app/$relativePath")
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
