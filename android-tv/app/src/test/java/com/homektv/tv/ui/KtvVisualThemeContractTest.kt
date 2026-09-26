package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvVisualThemeContractTest {
    @Test
    fun appSurfacesUseOneSharedBackgroundAndKeepVideoBlack() {
        val themes = parse("src/main/res/values/themes.xml")
        val appTheme = styleItems(themes, "Theme.HomeKtvTv")
        assertEquals("@drawable/ktv_screen_background", appTheme["android:windowBackground"])

        val setup = parse("src/main/res/layout/activity_setup.xml").documentElement
        assertEquals("@drawable/setup_background", androidAttribute(setup, "background"))
        assertBackgroundAlias("setup_background")

        val standby = findById(parse("src/main/res/layout/activity_main.xml"), "standbyPanel")
        assertEquals("@drawable/standby_background", androidAttribute(standby, "background"))
        assertBackgroundAlias("standby_background")

        val controllerRoot = findById(parse("src/main/res/layout/fragment_controller.xml"), "controllerRoot")
        assertEquals("@drawable/ktv_screen_background", androidAttribute(controllerRoot, "background"))

        val kioskRoot = findById(parse("src/main/res/layout/view_ktv_kiosk_overlay.xml"), "kioskRootOverlay")
        assertEquals("@drawable/ktv_dashboard_background", androidAttribute(kioskRoot, "background"))

        val player = findById(parse("src/main/res/layout/activity_main.xml"), "playerView")
        assertEquals("@android:color/black", androidAttribute(player, "background"))
    }

    @Test
    fun everyFullScreenAppSurfaceAppliesTheSelectedStageArtwork() {
        val setupActivity = locate("src/main/java/com/homektv/tv/ui/SetupActivity.kt").readText()
        assertTrue(
            "server setup and discovery must use the selected stage artwork",
            setupActivity.contains("KtvDashboardBackground.applyTo(binding.root)"),
        )

        val controller = locate("src/main/java/com/homektv/tv/ui/controller/ControllerFragment.kt").readText()
        assertTrue(
            "the phone controller must use the same stage artwork",
            controller.contains("KtvDashboardBackground.applyTo(binding.root)"),
        )

        val mainActivity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue(
            "standby must use the selected stage artwork",
            mainActivity.contains("KtvDashboardBackground.applyTo(binding.standbyPanel)"),
        )
        assertTrue(
            "the legacy queue page must use the selected stage artwork",
            mainActivity.contains("KtvDashboardBackground.applyTo(binding.queueOverlay)"),
        )
        assertTrue(
            "audio playback OSD must use the selected stage artwork",
            mainActivity.contains("KtvDashboardBackground.applyTo(audioUi.root)"),
        )
    }

    @Test
    fun dashboardAndCatalogCardsShareTheSelectedVisualTokens() {
        val colors = parse("src/main/res/values/colors.xml")
        assertEquals("#151719", colorValue(colors, "ktv_screen_bg").uppercase())
        assertEquals("#272A2E", colorValue(colors, "ktv_card_surface").uppercase())
        assertEquals("#F0C742", colorValue(colors, "gold").uppercase())

        val dashboardCard = locate("src/main/res/drawable/bg_dashboard_tile.xml").readText()
        assertTrue(dashboardCard.contains("@color/ktv_dashboard_card_surface"))
        assertTrue("reference dashboard tiles use a vertical graphite gradient", dashboardCard.contains("<gradient"))
        assertEquals("#31353B", colorValue(colors, "ktv_dashboard_card_surface").uppercase())
        assertEquals("#1A1F23", colorValue(colors, "ktv_dashboard_card_surface_bottom").uppercase())
        assertTrue(dashboardCard.contains("@dimen/ktv_dashboard_card_radius"))
        val dashboardFocus = locate("src/main/res/drawable/bg_dashboard_tile_focused.xml").readText()
        assertTrue(dashboardFocus.contains("@color/ktv_dashboard_gold"))
        assertTrue("focused tile surface must follow the reference warm fade", dashboardFocus.contains("<gradient"))
        assertEquals("#4B453D", colorValue(colors, "ktv_dashboard_card_focused").uppercase())
        assertEquals("#2B2723", colorValue(colors, "ktv_dashboard_card_focused_bottom").uppercase())

        val singerCard = locate("src/main/res/drawable/bg_singer_card.xml").readText()
        assertTrue(singerCard.contains("@color/ktv_card_surface"))
        assertTrue(singerCard.contains("@dimen/ktv_radius_card"))
        assertTrue(singerCard.contains("@color/gold"))
    }

    @Test
    fun foregroundTokensRemainReadableOnTheSharedBackgroundAndCards() {
        val colors = parse("src/main/res/values/colors.xml")
        val foregrounds = listOf(colorValue(colors, "text"), colorValue(colors, "dim"))
        val surfaces = listOf(colorValue(colors, "ktv_screen_bg"), colorValue(colors, "ktv_card_surface"))

        foregrounds.forEach { foreground ->
            surfaces.forEach { surface ->
                assertTrue(
                    "Text color $foreground must retain 4.5:1 contrast on $surface",
                    contrastRatio(foreground, surface) >= 4.5,
                )
            }
        }
    }

    @Test
    fun sharedSpacingAndBodyTypographyAreUsedByRealLayouts() {
        val main = parse("src/main/res/layout/activity_main.xml")
        val queueOverlay = findById(main, "queueOverlay")
        assertEquals("@drawable/ktv_screen_background", androidAttribute(queueOverlay, "background"))
        assertEquals(
            "@dimen/ktv_spacing_lg",
            androidAttribute(findById(main, "volumeBar"), "layout_marginStart"),
        )

        val setupContent = findById(parse("src/main/res/layout/activity_setup.xml"), "setupContent")
        assertEquals("@dimen/ktv_spacing_xl", androidAttribute(setupContent, "layout_marginTop"))

        val queueRow = parse("src/main/res/layout/item_queue_drawer_row.xml")
        val title = findById(queueRow, "txtQueueTitle")
        assertEquals("@style/TextAppearance.HomeKtv.Body", androidAttribute(title, "textAppearance"))
    }

    @Test
    fun pipSetupUpdateAndPhoneIconUseSharedSurfaceTokens() {
        assertDrawableUses(
            "bg_pip_frame",
            "@color/ktv_panel_surface",
            "@color/gold",
            "@dimen/ktv_radius_card",
        )
        assertDrawableUses(
            "setup_device_icon",
            "@color/ktv_surface_tint",
            "@color/ktv_border",
            "@dimen/ktv_radius_control",
        )
        assertDrawableUses(
            "vinyl_disc",
            "@color/ktv_card_surface",
            "@color/ktv_border",
            null,
        )

        val mutedUpdate = locate("src/main/res/drawable/bg_update_button_muted.xml").readText()
        assertTrue(mutedUpdate.contains("@color/ktv_panel_surface"))
        assertTrue(mutedUpdate.contains("@color/ktv_gold_tint"))
        assertTrue(mutedUpdate.contains("@dimen/ktv_radius_control"))
    }

    private fun assertBackgroundAlias(name: String) {
        val source = locate("src/main/res/drawable/$name.xml").readText()
        assertTrue("$name must delegate to the shared background", source.contains("@drawable/ktv_screen_background"))
    }

    private fun assertDrawableUses(name: String, fill: String, border: String, radius: String?) {
        val source = locate("src/main/res/drawable/$name.xml").readText()
        assertTrue("$name must use the shared fill $fill", source.contains(fill))
        if (border.isNotEmpty()) {
            assertTrue("$name must use the shared border $border", source.contains(border))
        }
        if (radius != null) {
            assertTrue("$name must use the shared radius $radius", source.contains(radius))
        }
    }

    private fun styleItems(document: org.w3c.dom.Document, styleName: String): Map<String, String> {
        return (0 until document.getElementsByTagName("style").length)
            .map { document.getElementsByTagName("style").item(it) as Element }
            .single { it.getAttribute("name") == styleName }
            .let { style ->
                (0 until style.getElementsByTagName("item").length)
                    .map { style.getElementsByTagName("item").item(it) as Element }
                    .associate { it.getAttribute("name") to it.textContent.trim() }
            }
    }

    private fun colorValue(document: org.w3c.dom.Document, name: String): String =
        (0 until document.getElementsByTagName("color").length)
            .map { document.getElementsByTagName("color").item(it) as Element }
            .single { it.getAttribute("name") == name }
            .textContent
            .trim()

    private fun findById(document: org.w3c.dom.Document, id: String): Element =
        (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/$id" }

    private fun androidAttribute(element: Element, name: String): String =
        element.getAttribute("android:$name")

    private fun contrastRatio(foreground: String, background: String): Double {
        val first = relativeLuminance(foreground)
        val second = relativeLuminance(background)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: String): Double {
        val channels = color.removePrefix("#").let { value ->
            listOf(0, 2, 4).map { value.substring(it, it + 2).toInt(16) / 255.0 }
        }
        val linear = channels.map { channel ->
            if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
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
