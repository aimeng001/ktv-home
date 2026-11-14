package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNonKioskFocusContractTest {
    @Test
    fun nonKioskDispatchDelegatesToPolicyWithoutSwallowingDirectionalDpadKeys() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val dispatch = main.substringAfter("override fun dispatchKeyEvent(event: KeyEvent): Boolean {")
            .substringBefore("\n    override fun ")

        assertTrue(dispatch.contains("KioskLifecyclePolicy.resolveNonKioskKeyAction("))
        assertFalse(dispatch.contains("KeyEvent.KEYCODE_DPAD_LEFT ->"))
        assertFalse(dispatch.contains("KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT ->"))
        assertFalse(dispatch.contains("KeyEvent.KEYCODE_DPAD_UP ->"))
        assertFalse(dispatch.contains("KeyEvent.KEYCODE_DPAD_DOWN ->"))
    }

    @Test
    fun topBarAndBottomDockSyncVerticalFocusRailsBasedOnKioskVisibility() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val dock = locate("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").readText()

        assertTrue(dock.contains("enum class DockFocusUpMode"))
        assertTrue(dock.contains("HEADER_BAR"))
        assertTrue(dock.contains("fun updateFocusUpMode("))
        assertTrue(main.contains("syncNonFullscreenFocusRails"))
        assertTrue(main.contains("DockFocusUpMode.HEADER_BAR"))
    }

    @Test
    fun legacyHomeAndMixerFocusHelpersTargetVisibleRemoteFocusableControls() {
        val dock = locate("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").readText()
        val focusHomeBody = dock.substringAfter("fun focusHome()")
            .substringBefore("fun focusMixer()")
        val focusMixerBody = dock.substringAfter("fun focusMixer()")
            .substringBefore("fun updateFocusUp")

        assertTrue(dock.contains("fun focusDefaultControl("))
        assertFalse("focusHome 不得聚焦 GONE 的 btnDockHome", focusHomeBody.contains("btnDockHome"))
        assertFalse("focusMixer 不得聚焦 GONE 的 btnDockMixer", focusMixerBody.contains("btnDockMixer"))
        assertTrue(dock.contains("requestRemoteFocus()"))

        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue(main.contains("remoteMenuReturnFocus"))
    }

    private fun locate(relativePath: String): File {
        var current = File(System.getProperty("user.dir").orEmpty())
        repeat(6) {
            val candidate = File(current, "android-tv/app/$relativePath")
            if (candidate.isFile) return candidate
            val localCandidate = File(current, relativePath)
            if (localCandidate.isFile) return localCandidate
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
