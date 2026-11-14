package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKeyboardLayoutContractTest {
    @Test
    fun qwertyAlphabetUsesFiveColumnsWithAllKeysReachable() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()
        val renderer = source.substringAfter("private fun renderQwertyKeys")
            .substringBefore("private fun setupActions")

        assertTrue(
            "字母键盘必须使用 5 列布局 (A-E, F-J, K-O, P-T, U-Y, Z+SPACE)",
            renderer.contains("grid.columnCount = 5"),
        )
        assertTrue(renderer.contains("('A'..'Z').forEach"))
    }

    @Test
    fun alphabetGridLivesInsideScrollableViewport() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_keyboard.xml"))
        val grid = document.getElementById("keyboardGrid")
            ?: (0 until document.getElementsByTagName("GridLayout").length)
                .map { document.getElementsByTagName("GridLayout").item(it) as org.w3c.dom.Element }
                .single { it.getAttribute("android:id") == "@+id/keyboardGrid" }
        val scroll = grid.parentNode as org.w3c.dom.Element

        assertEquals("ScrollView", scroll.tagName)
        assertEquals("@+id/keyboardGridScroll", scroll.getAttribute("android:id"))
        assertEquals("wrap_content", grid.getAttribute("android:layout_height"))
    }

    @Test
    fun resultKeysEnterKeyboardFromLeftAndRightEdgeKeysEnterModeRail() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()
        assertTrue(
            "默认键盘内容焦点目标应为歌曲结果",
            source.contains("contentFocusTargetId = R.id.browserResultsRecyclerView"),
        )
        assertTrue(
            "键盘最左列应向左回到当前页面的内容区",
            source.contains("nextFocusLeftId = contentFocusTargetId"),
        )
        assertTrue(
            "键盘最右列应向右进入当前模式按钮",
            source.contains("nextFocusRightId = activeModeButtonId()"),
        )
        assertTrue(
            "歌曲结果应能按遥控器右键进入当前键盘模式",
            source.contains("fun focusTargetFromResults()"),
        )
        assertTrue(
            "浏览器应能为当前内容更新键盘的返回焦点",
            source.contains("fun setContentFocusTarget(viewId: Int)"),
        )
        val controller = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(controller.contains("kioskKeyboard.focusTargetFromResults()"))
        assertTrue(controller.contains("kioskKeyboard.setContentFocusTarget(keyboardContentTarget.id)"))
        assertTrue(controller.contains("browser.browserSingerRecyclerView.nextFocusRightId"))
    }

    @Test
    fun fiveModeRailIsVisibleFocusableAndProvidesWorkingInputModes() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_keyboard.xml"))
        val buttons = (0 until document.getElementsByTagName("Button").length)
            .map { document.getElementsByTagName("Button").item(it) as org.w3c.dom.Element }
            .associateBy { it.getAttribute("android:id") }

        listOf("btnModeHandwrite", "btnModeZhuyin").forEach { id ->
            val button = buttons.getValue("@+id/$id")
            assertEquals("visible", button.getAttribute("android:visibility"))
            assertEquals("true", button.getAttribute("android:focusable"))
        }
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()
        assertTrue(source.contains("Mode.HANDWRITING"))
        assertTrue(source.contains("Mode.ZHUYIN"))
        assertTrue("手写模式必须提供实际可绘制画板", source.contains("KtvHandwritingCanvasView"))
        assertTrue("注音模式必须使用明确的符号映射表", source.contains("BopomofoPinyinMapper"))
        assertFalse("键盘不得展示手写不可用占位文案", source.contains("手写区域暂不可用"))
        assertFalse("键盘不得展示注音不可用占位文案", source.contains("当前没有可用的注音候选转换"))
        assertTrue(source.contains("requestSystemInputMode()"))
    }

    @Test
    fun keyboardKeepsEnoughRowsVisibleBesideFilterAndRetryPanel() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_three_column_browser.xml"))
        val views = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) as org.w3c.dom.Element }
        val keyboard = views.single { it.getAttribute("android:id") == "@+id/browserKeyboardContainer" }
        val filter = document.getElementsByTagName("ScrollView").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                .single { it.getAttribute("android:id") == "@+id/browserFilterScroll" }
        }

        assertEquals("4", keyboard.getAttribute("android:layout_weight"))
        assertEquals("1", filter.getAttribute("android:layout_weight"))
    }

    @Test
    fun alphabetKeysShrinkToFitTheVisibleViewportBeforeScrolling() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()
        assertTrue(source.contains("resizeGridRows(rowCount = 6)"))
        assertTrue(source.contains("coerceIn(dpToPx(17f), dpToPx(48f))"))
        assertTrue(source.contains("nextFocusDownId = contentFocusTargetId"))
    }

    @Test
    fun handwritingUsesCandidateStripAndFocusableCanvasInsteadOfPlaceholder() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()
        val panel = source.substringAfter("private fun renderHandwritingPanel")
            .substringBefore("private fun renderZhuyinKeys")

        assertTrue(panel.contains("常用字"))
        assertTrue(panel.contains("COMMON_SONG_TITLE_CHARACTERS"))
        assertTrue(panel.contains("KtvHandwritingCanvasView"))
        assertTrue(panel.contains("wireHandwritingFocus"))
        assertTrue(source.contains("consumeBackForActiveDrawing"))
        assertFalse(source.contains("renderUnsupportedInputMode"))
    }

    @Test
    fun numericPanelUsesLiteralDigitsWithZeroCenteredOnFourthRow() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()
        val panel = source.substringAfter("private fun renderNumberKeys")
            .substringBefore("private fun resizeGridRows")

        assertTrue(panel.contains("grid.columnCount = 3"))
        assertTrue(panel.contains("grid.rowCount = 4"))
        assertTrue(panel.contains("\"0\" to (3 to 1)"))
        assertTrue(panel.contains("session.onNumericKeyPressed(label[0])"))
        assertFalse(panel.contains("\"清空\""))
        assertFalse(panel.contains("\"退格\""))
    }

    @Test
    fun normalizedSearchQueryIsDispatchedOnlyWhenItsEffectiveValueChanges() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()
        assertTrue(source.contains("T9PinyinSearchPolicy.normalizeSearchKeyword(keyword)"))
        assertTrue(source.contains("if (searchKeyword != lastDispatchedSearchKeyword)"))
    }

    @Test
    fun activeModeUsesProductColorWhileFocusedStateRemainsSeparate() {
        val drawable = locate("src/main/res/drawable/bg_ktv_ime_mode_btn.xml").readText()
        assertTrue(drawable.contains("android:state_focused=\"true\""))
        assertTrue(drawable.contains("android:state_selected=\"true\""))
        assertTrue(drawable.contains("#25FFFFFF"))
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
