package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SetupConnectionPageContractTest {
    @Test
    fun discoveryAndManualConnectionHaveSeparatePagesWithoutLosingExistingControls() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/activity_setup.xml"))
        val elements = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) }
            .filterIsInstance<Element>()
        val byId = elements.associateBy { it.getAttribute("android:id") }

        assertEquals("@string/setup_scan_idle", byId.getValue("@+id/txtScanStatus").getAttribute("android:text"))
        val discovery = byId.getValue("@+id/setupDiscoveryPage")
        val manual = byId.getValue("@+id/setupManualPage")
        assertEquals("visible", discovery.getAttribute("android:visibility"))
        assertEquals("gone", manual.getAttribute("android:visibility"))
        assertEquals("@+id/setupDiscoveryPage", ancestorWithId(byId.getValue("@+id/historyContainer")))
        assertEquals("@+id/setupDiscoveryPage", ancestorWithId(byId.getValue("@+id/lanContainer")))
        listOf("inputHost", "inputCredential", "btnConnect")
            .forEach { id -> assertEquals("@+id/setupManualPage", ancestorWithId(byId.getValue("@+id/$id"))) }
        listOf("inputNickname", "modeGroup", "modePlayer", "modeController", "modeCombined")
            .forEach { id -> assertFalse("精简后的连接页不应再包含 $id", byId.containsKey("@+id/$id")) }

        assertTrue(byId.containsKey("@+id/btnOpenManualSetup"))
        assertTrue(byId.containsKey("@+id/btnSetupBackToDiscovery"))
    }

    @Test
    fun existingSetupActivitySwitchesPagesWithoutReplacingConnectionActions() {
        val source = locate("src/main/java/com/homektv/tv/ui/SetupActivity.kt").readText()

        assertTrue(source.contains("binding.btnOpenManualSetup.setOnClickListener { showSetupPage(SetupPage.MANUAL) }"))
        assertTrue(source.contains("binding.btnSetupBackToDiscovery.setOnClickListener { showSetupPage(SetupPage.DISCOVERY) }"))
        assertTrue(source.contains("binding.btnRefresh.setOnClickListener { startScan() }"))
        assertTrue(source.contains("binding.btnConnect.setOnClickListener { submitManual() }"))
        assertTrue(source.contains("binding.setupDiscoveryPage.visibility = if (showingDiscovery) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("binding.setupManualPage.visibility = if (showingDiscovery) View.GONE else View.VISIBLE"))
        assertTrue(source.contains("binding.root.post { firstFocusableView()?.requestFocus() }"))
        assertTrue(source.contains("focusables += binding.inputCredential"))
        assertTrue(source.contains("focusables += binding.btnConnect"))
        assertFalse(source.contains("applyResponsiveLayout"))
        assertFalse(source.contains("applyOrientationForMode"))
        assertTrue(source.contains("outState.putString(KEY_SETUP_PAGE, setupPage.name)"))
        assertFalse(source.substringAfter("private fun showSetupPage").substringBefore("private fun startRhythm").contains("setText("))
    }

    private fun ancestorWithId(element: Element): String? {
        var current = element.parentNode
        while (current is Element) {
            val id = current.getAttribute("android:id")
            if (id.isNotBlank()) return id
            current = current.parentNode
        }
        return null
    }

    @Test
    fun setupActivityPrefillsServerHostConnectsFocusToSubmitButtonAndScrollsFormScrollView() {
        val source = locate("src/main/java/com/homektv/tv/ui/SetupActivity.kt").readText()
        val onCreateFn = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("override fun onSaveInstanceState")
        assertTrue(
            "onCreate 在未传入 candidate 且无恢复状态时必须回填已配置的 config.serverHost",
            onCreateFn.contains("config.serverHost") && onCreateFn.contains("inputHost.text.isNullOrBlank()"),
        )

        val focusFn = source.substringAfter("private fun rebuildFocusChain()")
            .substringBefore("private fun firstFocusableView")
        assertTrue(
            "手动连接页焦点链须依次包含返回、地址、密钥与连接按钮",
            focusFn.contains("binding.inputCredential") &&
                focusFn.contains("binding.btnConnect") &&
                !focusFn.contains("modeGroup"),
        )
        assertTrue(
            "焦点滚动应作用在可滚动的 setupFormScroll 而非外层 FrameLayout setupScroll",
            source.contains("binding.setupFormScroll.post"),
        )
    }

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
