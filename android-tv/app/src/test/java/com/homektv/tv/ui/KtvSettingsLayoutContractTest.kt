package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvSettingsLayoutContractTest {
    @Test
    fun settingsKeepTwoColumnsAndDpadEntryPoints() {
        val document = parse("src/main/res/layout/dialog_ktv_settings.xml")
        val byId = elements(document, "*").associateBy { it.getAttribute("android:id") }

        assertTrue(byId.containsKey("@+id/settingColumnLeft"))
        assertTrue(byId.containsKey("@+id/settingColumnRight"))
        assertTrue(byId.containsKey("@+id/settingsColumnGap"))
        assertTrue(byId.containsKey("@+id/settingsStageBackdrop"))
        assertTrue(byId.containsKey("@+id/settingsGrid"))
        assertEquals("match_parent", byId.getValue("@+id/settingsDialogRoot").getAttribute("android:layout_width"))
        assertEquals("match_parent", byId.getValue("@+id/settingsDialogRoot").getAttribute("android:layout_height"))
        assertEquals("@android:color/transparent", byId.getValue("@+id/settingsDialogRoot").getAttribute("android:background"))
        assertEquals("48dp", byId.getValue("@+id/settingsStageBackdrop").getAttribute("android:layout_marginTop"))
        assertEquals("104dp", byId.getValue("@+id/settingsStageBackdrop").getAttribute("android:layout_marginBottom"))
        assertEquals("@android:color/transparent", byId.getValue("@+id/settingsStageBackdrop").getAttribute("android:background"))
        assertFalse(byId.containsKey("@+id/txtSettingsTitle"))
        assertFalse(byId.containsKey("@+id/btnSettingsClose"))

        val factory = locate("src/main/java/com/homektv/tv/ui/KtvSettingsRowFactory.kt").readText()
        listOf("nextFocusUpId", "nextFocusDownId", "nextFocusLeftId", "nextFocusRightId").forEach {
            assertTrue("Every settings row needs an explicit D-pad route: $it", factory.contains(it))
        }
        assertTrue(factory.contains("require(leftEntries.size == rightEntries.size)"))
        assertTrue("Rows must use the responsive measured pixel policy", factory.contains("metrics.rowHeightPx"))
        assertTrue(factory.contains("bottomMargin = metrics.rowGapPx"))
        assertTrue(factory.contains("scaledSpPx(14f)"))
        assertTrue(factory.contains("scaledSpPx(12f)"))
        assertTrue(factory.contains("#aa1fef"))
        assertTrue(factory.contains("KtvSettingsIndicator.SWITCH"))
        assertTrue(factory.contains("nextFocusUpId = if (index == 0) row.id"))
        assertTrue(factory.contains("nextFocusDownId = if (index == leftRows.lastIndex) row.id"))

        val card = locate("src/main/res/drawable/bg_ktv_settings_card.xml").readText()
        assertTrue(card.contains("#20FFFFFF"))

        val activity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue("Settings must cover the full activity while preserving header and dock", activity.contains("setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)"))
        assertTrue(activity.contains("setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))"))
        assertTrue(activity.contains("KtvDashboardBackground.applyTo(settingsBackdrop)"))
        assertTrue(activity.contains("KtvSettingsLayoutPolicy.forScreenHeight"))
        assertTrue(activity.contains("settingsGrid.translationY = metrics.translationYPx.toFloat()"))
        assertTrue(activity.contains("settingsGrid.setPadding(metrics.horizontalInsetPx"))
    }

    @Test
    fun topBarActionsRemainVisibleAndUseRealHomeKtvFlows() {
        val activity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val toolbar = parse("src/main/res/layout/activity_main.xml")
        val byId = elements(toolbar, "*").associateBy { it.getAttribute("android:id") }

        listOf("btnTopSystem", "btnTopMobileQr", "btnTopSettings", "btnTopSearch", "btnTopLibraryScan").forEach { id ->
            val action = byId.getValue("@+id/$id")
            assertEquals("36dp", action.getAttribute("android:layout_width"))
            assertEquals("36dp", action.getAttribute("android:layout_height"))
            assertEquals("true", action.getAttribute("android:focusable"))
            assertEquals("@drawable/bg_ktv_header_action", action.getAttribute("android:background"))
        }
        assertFalse("The visible toolbar must not append a Home KTV logo", byId.containsKey("@+id/imgHeaderBrand"))
        assertTrue("The standby logo remains available", byId.containsKey("@+id/imgStandbyLogo"))
        assertTrue("Search remains visible in every device mode", activity.contains("binding.btnTopSearch.visibility = View.VISIBLE"))
        assertTrue("Global search must remember its origin", activity.contains("openGlobalSearch(binding.btnTopSearch)"))
        assertTrue(activity.contains("KtvSystemInfoDialog("))
        assertTrue(activity.contains("secondaryActionLabel = \"服务与设备\""))
        assertTrue(activity.contains("SetupActivity.EXTRA_RETURN_TO_CALLER"))
        assertTrue(activity.contains("KtvSettingsAction.OPEN_LIBRARY_SCAN"))
        assertTrue(activity.contains("KtvSettingsAction.OPEN_MEDIA_FEEDBACK"))
        assertTrue(activity.contains("globalSearchEnabled = { tvSettingsStore.snapshot().globalSearchEnabled }"))
        assertTrue(activity.contains("kioskController.refreshGlobalSearchPreference()"))
        assertTrue(activity.contains("LibraryApi(transport).requestScan()"))
        assertTrue(activity.contains("LibraryScanFeedbackPolicy.failureMessage(result.error.message)"))
        assertTrue(activity.contains("R.layout.dialog_library_scan_confirm"))

        val scanDialog = parse("src/main/res/layout/dialog_library_scan_confirm.xml")
        val scanById = elements(scanDialog, "*").associateBy { it.getAttribute("android:id") }
        assertTrue(scanById.containsKey("@android:id/button2"))
        assertTrue(scanById.containsKey("@android:id/button1"))
        assertEquals("@android:id/button1", scanById.getValue("@android:id/button2").getAttribute("android:nextFocusRight"))
        assertEquals("@android:id/button2", scanById.getValue("@android:id/button1").getAttribute("android:nextFocusLeft"))
        assertTrue(activity.contains("content.findViewById<View>(android.R.id.button2)"))
        assertTrue(activity.contains("content.findViewById<View>(android.R.id.button1)"))

        val controller = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(controller.contains("private var globalSearchReturnFocus: View? = null"))
        assertTrue(controller.contains("if (globalSearchReturnFocus != null)"))
        assertTrue(controller.contains("KtvGlobalSearchPolicy.shouldShowKeyboard"))
        assertTrue(controller.contains("KtvGlobalSearchPolicy.targetTabForQuery"))
        assertTrue(controller.contains("returnFocus.requestFocus()"))
    }

    @Test
    fun originalTwelveSettingRowsRemainVisibleWithoutInventedMediaWrites() {
        val activity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val catalog = locate("src/main/java/com/homektv/tv/ui/KtvSettingsCatalog.kt").readText()
        assertFalse(activity.contains("settingCardScanLibrary"))
        assertFalse(activity.contains("settingCardDeviceMode"))
        listOf("25.4 万首", "在线媒体反馈已开启", "自动删歌已开启").forEach { phrase ->
            assertFalse(catalog.contains(phrase))
        }
        listOf("自动删歌", "设备同步").forEach { label ->
            assertTrue("The original setting position must remain present: $label", catalog.contains(label))
        }
        assertFalse("Automatic delete must never call a media mutation", activity.contains("deleteSourceMedia"))
        assertFalse("Device sync must never create a media copy", activity.contains("copyExternalMedia"))
    }

    @Test
    fun systemAndInfoDialogsUseUnifiedGlassModalWithFocusNavigation() {
        val dialogXml = parse("src/main/res/layout/dialog_ktv_system_info.xml")
        val byId = elements(dialogXml, "*").associateBy { it.getAttribute("android:id") }

        assertEquals("@drawable/player_glass", byId.getValue("@+id/systemInfoDialogRoot").getAttribute("android:background"))
        assertTrue(byId.containsKey("@+id/txtSystemInfoTitle"))
        assertTrue(byId.containsKey("@+id/txtSystemInfoMessage"))
        val secondary = byId.getValue("@+id/btnSystemInfoSecondary")
        val confirm = byId.getValue("@+id/btnSystemInfoConfirm")
        assertEquals("true", secondary.getAttribute("android:focusable"))
        assertEquals("true", confirm.getAttribute("android:focusable"))
        assertEquals("@id/btnSystemInfoConfirm", secondary.getAttribute("android:nextFocusRight"))
        assertEquals("@id/btnSystemInfoSecondary", confirm.getAttribute("android:nextFocusLeft"))

        val dialogClass = locate("src/main/java/com/homektv/tv/ui/KtvSystemInfoDialog.kt").readText()
        assertTrue(dialogClass.contains("class KtvSystemInfoDialog("))
        assertTrue(dialogClass.contains("btnSystemInfoConfirm.requestRemoteFocus()"))

        val activity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val systemMethod = activity.substringAfter("private fun showSystemInfoDialog()")
            .substringBefore("private fun launchServiceAndDeviceSettings()")
        val infoMethod = activity.substringAfter("private fun showInformationalDialog(")
            .substringBefore("private fun showLibraryScanConfirmDialog(")
        assertFalse("系统信息与提示弹窗不得继续使用原生 AlertDialog.Builder", systemMethod.contains("AlertDialog.Builder"))
        assertFalse("通用提示弹窗与下载说明不得继续使用原生 AlertDialog.Builder", infoMethod.contains("AlertDialog.Builder"))
        assertTrue(activity.contains("showTrackedInfoModal("))
    }

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(locate(path))

    private fun elements(document: org.w3c.dom.Document, tag: String): List<Element> =
        (0 until document.getElementsByTagName(tag).length)
            .map { document.getElementsByTagName(tag).item(it) }
            .filterIsInstance<Element>()

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
