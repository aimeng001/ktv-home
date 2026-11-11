package com.homektv.tv.ui

import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvThemeTokenTest {
    @Test
    fun commercialPaletteUsesTheSpecifiedColors() {
        val colorFiles = listOf(
            locate("src/main/res/values/colors_ktv_theme.xml"),
            locate("src/main/res/values/colors.xml"),
        )
        val colors = colorFiles.flatMap { file ->
            val document = parse(file)
            (0 until document.getElementsByTagName("color").length)
                .map { document.getElementsByTagName("color").item(it) as Element }
        }

        val expected = mapOf(
            "ktv_bg_main" to "#14171D",
            "ktv_card_surface" to "#20252E",
            "ktv_focus_blue" to "#3182FB",
            "ktv_vocal_green" to "#10B981",
            "ktv_badge_orange" to "#F59E0B",
        )
        expected.forEach { (name, value) ->
            assertEquals("$name must be defined exactly once", 1, colors.count { it.getAttribute("name") == name })
            assertEquals(value, colors.single { it.getAttribute("name") == name }.textContent.trim().uppercase())
        }
    }

    @Test
    fun dashboardDimensionsMatchThe1080pReference() {
        val document = parse(locate("src/main/res/values/dimens_ktv_dashboard.xml"))
        val dimensions = (0 until document.getElementsByTagName("dimen").length)
            .map { document.getElementsByTagName("dimen").item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }

        assertEquals("104dp", dimensions["ktv_dashboard_bottom_dock_height"])
        assertEquals("185dp", dimensions["ktv_dashboard_pip_width"])
        assertEquals("104dp", dimensions["ktv_dashboard_pip_height"])
        assertEquals("320dp", dimensions["ktv_dashboard_large_tile_width"])
        assertEquals("180dp", dimensions["ktv_dashboard_large_tile_height"])
        assertEquals("12dp", dimensions["ktv_theme_card_corner_radius"])
        assertEquals("2.5dp", dimensions["ktv_theme_focus_stroke_width"])
    }

    @Test
    fun focusedCardSelectorAndStyleProvideBlueFocusAnd105Scale() {
        val card = parse(locate("src/main/res/drawable/bg_ktv_card_focused.xml"))
        val focusedItem = (0 until card.getElementsByTagName("item").length)
            .map { card.getElementsByTagName("item").item(it) as Element }
            .single { it.getAttribute("android:state_focused") == "true" }
        val focusedElements = (0 until focusedItem.getElementsByTagName("*").length)
            .map { focusedItem.getElementsByTagName("*").item(it) as Element }
        assertTrue(focusedElements.any { it.getAttribute("android:color") == "@color/ktv_focus_blue" })
        assertTrue(focusedElements.any { it.getAttribute("android:width") == "@dimen/ktv_theme_focus_stroke_width" })
        assertTrue(focusedElements.any { it.getAttribute("android:radius") == "@dimen/ktv_theme_card_corner_radius" })

        val styles = parse(locate("src/main/res/values/styles.xml"))
        val cardStyle = styleItems(styles, "Widget.HomeKtv.CommercialCard")
        assertEquals("@drawable/bg_ktv_card_focused", cardStyle["android:background"])
        assertEquals("@animator/ktv_card_focus_scale", cardStyle["android:stateListAnimator"])

        val animator = locate("src/main/res/animator/ktv_card_focus_scale.xml").readText()
        assertTrue(animator.contains("android:state_focused=\"true\""))
        assertTrue(animator.contains("android:valueTo=\"1.05\""))
    }

    @Test
    fun dashboardDockDoesNotAddABandBehindControlsAndPipKeepsItsFrame() {
        val dockDocument = parse(locate("src/main/res/layout/view_ktv_bottom_dock.xml"))
        val dockRoot = dockDocument.documentElement
        assertEquals("@+id/ktv_bottom_dock", dockRoot.getAttribute("android:id"))
        assertEquals("@android:color/transparent", dockRoot.getAttribute("android:background"))

        val videoFrame = locate("src/main/res/drawable/bg_ktv_dock_video_frame.xml").readText()
        assertTrue(videoFrame.contains("@android:color/black"))
        assertTrue(videoFrame.contains("@color/ktv_focus_blue"))
    }

    @Test
    fun kioskDashboardDoesNotShowStandbyBrandBesideTheFiveHeaderActions() {
        assertFalse(KtvHeaderBrandVisibilityPolicy.shouldShowStandbyBrand(canOpenKiosk = true))
        assertTrue(KtvHeaderBrandVisibilityPolicy.shouldShowStandbyBrand(canOpenKiosk = false))
    }

    private fun styleItems(document: org.w3c.dom.Document, styleName: String): Map<String, String> =
        (0 until document.getElementsByTagName("style").length)
            .map { document.getElementsByTagName("style").item(it) as Element }
            .single { it.getAttribute("name") == styleName }
            .let { style ->
                (0 until style.getElementsByTagName("item").length)
                    .map { style.getElementsByTagName("item").item(it) as Element }
                    .associate { it.getAttribute("name") to it.textContent.trim() }
            }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath")
    }

    @Test
    fun bottomDockControlsHaveTopDrawablesAndThreeColumnTokensAreDefined() {
        val dockDoc = parse(locate("src/main/res/layout/view_ktv_bottom_dock.xml"))
        val byId = (0 until dockDoc.getElementsByTagName("*").length)
            .map { dockDoc.getElementsByTagName("*").item(it) as Element }
            .associateBy { it.getAttribute("android:id") }

        val buttonsWithIcons = listOf(
            "@+id/btnDockCheer",
            "@+id/btnDockRestart",
            "@+id/btnDockMixer",
        )
        buttonsWithIcons.forEach { id ->
            val element = byId[id]
            assertNotNull("$id 必须存在于底栏布局", element)
            val drawableTop = element?.getAttribute("android:drawableTop")
            assertTrue("$id 必须配置 android:drawableTop 矢量图标以保证高度对齐", !drawableTop.isNullOrBlank() && drawableTop.startsWith("@drawable/"))
        }

        val dimenDoc = parse(locate("src/main/res/values/dimens_ktv_dashboard.xml"))
        val dimensions = (0 until dimenDoc.getElementsByTagName("dimen").length)
            .map { dimenDoc.getElementsByTagName("dimen").item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }

        assertEquals("200dp", dimensions["ktv_browser_nav_rail_width"])
        assertEquals("240dp", dimensions["ktv_browser_center_col_width"])
    }


    @Test
    fun ghostButtonUsesKtvFocusBlue() {
        val ghost = locate("src/main/res/drawable/btn_ghost.xml").readText()
        assertTrue(
            "btn_ghost 在获焦时必须使用 @color/ktv_focus_blue 统一蓝光边框",
            ghost.contains("@color/ktv_focus_blue"),
        )
    }

    @Test
    fun cardFocusedDrawableSupportsSelectedState() {
        val card = locate("src/main/res/drawable/bg_ktv_card_focused.xml").readText()
        assertTrue(
            "bg_ktv_card_focused 必须支持 android:state_selected 以便与内部按钮获焦联动",
            card.contains("android:state_selected=\"true\""),
        )
    }

    @Test
    fun androidUiUsesHomeAssetsAndOnlyTheWhitelistedIktv50Cutouts() {
        val mainDir = locate("src/main/AndroidManifest.xml").parentFile ?: error("Missing src/main directory")
        val approvedCutouts = setOf(
            "ktv_ref_v50_tile_hot_new",
            "ktv_ref_v50_tile_tiktok",
            "ktv_ref_v50_tile_singers_01",
            "ktv_ref_v50_tile_songs_01",
            "ktv_ref_v50_tile_singers_02",
            "ktv_ref_v50_tile_songs_02",
            "ktv_ref_v50_tile_singers_03",
            "ktv_ref_v50_tile_songs_03",
            "ktv_ref_v50_tile_variety",
            "ktv_ref_v50_tile_language",
            "ktv_ref_v50_tile_topic",
            "ktv_ref_v50_tile_featured",
            "ktv_ref_v50_tile_favorite",
            "ktv_ref_v50_tile_local",
            "ktv_ref_v50_variety_zghsy",
            "ktv_ref_v50_variety_zghgq",
            "ktv_ref_v50_variety_zgmzs",
            "ktv_ref_v50_variety_zgzqy",
            "ktv_ref_v50_variety_zmhs",
            "ktv_ref_v50_variety_wwgk",
            "ktv_ref_v50_variety_wsgs",
            "ktv_ref_v50_variety_qnxz",
            "ktv_ref_v50_dock_tone_down",
            "ktv_ref_v50_dock_tone_up",
            "ktv_ref_v50_dock_download",
            "ktv_ref_v50_player_pause",
            "ktv_ref_v50_player_play",
            "ktv_ref_v50_player_vocal",
            "ktv_ref_v50_player_restart",
            "ktv_ref_v50_player_next",
            "ktv_ref_v50_player_volume_down",
            "ktv_ref_v50_player_volume_up",
            "ktv_ref_v50_player_mute",
            "ktv_ref_v50_player_unmute",
            "ktv_ref_v50_player_kiosk",
            "ktv_ref_v50_player_queue",
            "ktv_ref_v50_player_home",
            "ktv_ref_v50_player_rewind",
            "ktv_ref_v50_player_forward",
            "ktv_ref_v50_player_seek_thumb",
            "ktv_ref_v50_queue_drawer_bg",
            "ktv_ref_v50_settings_back",
            "ktv_ref_v50_settings_cloud",
            "ktv_ref_v50_settings_delete",
            "ktv_ref_v50_settings_device",
            "ktv_ref_v50_settings_download",
            "ktv_ref_v50_settings_feedback",
            "ktv_ref_v50_settings_fullscreen",
            "ktv_ref_v50_settings_search",
            "ktv_ref_v50_settings_sync",
            "ktv_ref_v50_settings_usb",
        )
        val projectFiles = mainDir.walkTopDown().filter(File::isFile).toList()
        val packagedCutouts = projectFiles
            .filter { it.name.startsWith("ktv_ref_") }
            .map { it.nameWithoutExtension }
            .toSet()
        assertEquals("只允许经审核、用于对应 UI 位置的 IKTV 50 原画切图", approvedCutouts, packagedCutouts)

        val referencedCutouts = projectFiles
            .filter { it.extension in setOf("xml", "kt", "java") }
            .flatMap { file -> Regex("ktv_ref_v50_[A-Za-z0-9_]+").findAll(file.readText()).map { it.value }.toList() }
            .toSet()
        assertEquals("源码引用的 IKTV 切图也必须完全落在批准名单内", approvedCutouts, referencedCutouts)

        val drawableDir = locate("src/main/res/drawable/bg_ktv_bottom_dock.xml").parentFile
        listOf("ic_search.xml", "ic_settings.xml", "ic_server.xml", "ic_library_scan.xml", "ic_wifi.xml")
            .forEach { assetName ->
                val file = File(drawableDir, assetName)
                assertTrue("Home KTV 自有图标 $assetName 必须存在", file.exists() && file.length() > 0)
            }
    }

    @Test
    fun fullscreenAccompanimentButtonUsesTheOriginalDiscIcon() {
        val icon = locate("src/main/res/drawable-nodpi/ktv_ref_v50_player_vocal.png")
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(icon.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        assertEquals(
            "fullscreen 伴唱按钮必须使用 IKTV 50.0.4 原版圆盘切图",
            "28bec027a1f062a07587fcc1efc2ffd4601c7cac8f3c3511a5337a32c03627e3",
            sha256,
        )

        val panel = locate("src/main/java/com/homektv/tv/ui/KtvFullscreenPlaybackPanel.kt").readText()
        assertTrue(
            "伴唱按钮必须绑定原版圆盘切图，而不是 Dock 的绿色音频切换图标",
            panel.contains("ActionSpec(FullscreenPlaybackAction.VOCAL, R.id.btnFullscreenVocal, R.drawable.ktv_ref_v50_player_vocal"),
        )
    }
}
