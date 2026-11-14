package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDialogFocusTest {

    @Test
    fun settingsRowsSupportTouchModeFocusForRemoteController() {
        val rowFactorySource = locate("src/main/java/com/homektv/tv/ui/KtvSettingsRowFactory.kt").readText()

        assertTrue(
            "KtvSettingsRowView must have isFocusableInTouchMode = true so requestRemoteFocus succeeds in touch mode",
            rowFactorySource.contains("isFocusableInTouchMode = true"),
        )
        assertFalse(
            "KtvSettingsRowView must not disable touch mode focus",
            rowFactorySource.contains("isFocusableInTouchMode = false"),
        )
    }

    @Test
    fun openingSettingsDialogClearsTopButtonFocusBeforeFocusingFirstRow() {
        val activitySource = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val settingsDialogFn = activitySource.substringAfter("private fun showKtvSettingsDialog() {")
            .substringBefore("private fun settingsEntries()")

        assertTrue(
            "showKtvSettingsDialog must clear top button focus or request focus on download_path",
            settingsDialogFn.contains("rows[\"download_path\"]") &&
                (settingsDialogFn.contains("clearFocus()") || settingsDialogFn.contains("returnFocus.clearFocus()")),
        )
    }

    private fun locate(relativePath: String): File {
        var current = File(System.getProperty("user.dir").orEmpty())
        repeat(6) {
            val candidate = File(current, "android-tv/app/$relativePath")
            if (candidate.isFile) return candidate
            val candidateDirect = File(current, relativePath)
            if (candidateDirect.isFile) return candidateDirect
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
