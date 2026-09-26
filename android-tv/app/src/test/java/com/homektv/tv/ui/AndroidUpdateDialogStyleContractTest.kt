package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidUpdateDialogStyleContractTest {
    @Test
    fun updateDialogUsesDedicatedDarkGoldTheme() {
        val themes = parse("src/main/res/values/themes.xml")
        val updateTheme = (0 until themes.getElementsByTagName("style").length)
            .map { themes.getElementsByTagName("style").item(it) as Element }
            .singleOrNull { it.getAttribute("name") == "Theme.HomeKtvTv.UpdateDialog" }
        assertTrue("update modal should have an isolated theme", updateTheme != null)

        val items = (0 until updateTheme!!.getElementsByTagName("item").length)
            .map { updateTheme.getElementsByTagName("item").item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
        assertEquals("@color/ktv_panel_surface", items["android:colorBackground"])
        assertEquals("@color/text", items["android:textColorPrimary"])
        assertEquals("@color/gold", items["android:colorAccent"])
    }

    @Test
    fun updatePromptUsesDedicatedThemeWithoutChangingActions() {
        val source = locate("src/main/java/com/homektv/tv/ui/AndroidUpdateManager.kt").readText()

        assertTrue(source.contains("import androidx.appcompat.app.AlertDialog"))
        assertTrue(source.contains("ContextThemeWrapper(activity, com.homektv.tv.R.style.Theme_HomeKtvTv_UpdateDialog)"))
        assertTrue(source.contains("private fun showStyledUpdateDialog(builder: AlertDialog.Builder)"))
        assertTrue(source.contains("setBackgroundDrawableResource(com.homektv.tv.R.drawable.update_dialog_background)"))
        assertTrue(source.contains("setNegativeButton(\"暂不更新\", null)"))
        assertTrue(source.contains("setPositiveButton(\"去下载\")"))
    }

    @Test
    fun styledWindowAndEqualWidthActionsAreAppliedAfterDialogShow() {
        val source = locate("src/main/java/com/homektv/tv/ui/AndroidUpdateManager.kt").readText()
        val showPosition = source.indexOf("dialog.show()")
        val backgroundPosition = source.indexOf("setBackgroundDrawableResource(com.homektv.tv.R.drawable.update_dialog_background)")

        assertTrue("window styling must happen after the dialog is attached", showPosition >= 0 && backgroundPosition > showPosition)
        assertTrue(source.contains("styleUpdateDialogActionButtons(dialog)"))
        assertTrue(source.contains("negativeParams.width = 0"))
        assertTrue(source.contains("negativeParams.weight = 1f"))
        assertTrue(source.contains("positiveParams.width = 0"))
        assertTrue(source.contains("positiveParams.weight = 1f"))
        assertTrue(locate("src/main/res/drawable/bg_update_button_gold.xml").isFile)
        assertTrue(locate("src/main/res/drawable/bg_update_button_muted.xml").isFile)
    }

    private fun parse(relativePath: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(locate(relativePath))

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath")
    }
}
