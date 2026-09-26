package com.homektv.tv.ui.kiosk

import com.homektv.tv.ui.calculatePlaybackProgress
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvDashboardReferenceFidelityContractTest {
    @Test
    fun dashboardUsesItsOwnLayeredGraphiteBackgroundInsteadOfTheFlatSharedSurface() {
        val root = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml").documentElement
        assertEquals("@drawable/ktv_dashboard_background", root.getAttribute("android:background"))

        val drawable = locate("src/main/res/drawable/ktv_dashboard_background.xml").readText()
        assertTrue("dashboard background must preserve the reference's vertical graphite fade", drawable.contains("<gradient"))
        assertEquals(
            listOf("#222226", "#1E1F24", "#191B1D", "#121718", "#0C0E12", "#0A0B0D", "#0A0B0D"),
            KtvDashboardBackground.referenceStops.map { it.colorHex },
        )
        assertEquals(listOf(0.000f, 0.085f, 0.213f, 0.426f, 0.638f, 0.851f, 1.000f),
            KtvDashboardBackground.referenceStops.map { it.position })
    }

    @Test
    fun visibleDashboardLabelsAndTileDensityMatchTheReference() {
        assertEquals(
            listOf("搜索点歌", "歌星点歌", "分类点歌", "语种点歌", "我的收藏", "曾经点唱", "已点歌曲"),
            KtvDashboardTile.dashboardEntries.map { it.title },
        )

        val tile = parse("src/main/res/layout/item_kiosk_dashboard_tile.xml")
        val subtitle = (0 until tile.getElementsByTagName("TextView").length)
            .map { tile.getElementsByTagName("TextView").item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/txtTileSubtitle" }
        assertEquals("gone", subtitle.getAttribute("android:visibility"))

        val overlay = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val headerTabs = listOf("tabDashboard", "tabPinyin", "tabSingers", "tabCategories", "tabLanguages", "tabFavorites", "tabHistory")
            .map { id ->
                (0 until overlay.getElementsByTagName("Button").length)
                    .map { overlay.getElementsByTagName("Button").item(it) as Element }
                    .single { it.getAttribute("android:id") == "@+id/$id" }
                    .getAttribute("android:text")
            }
        assertEquals(
            listOf("首页", "搜索", "歌手", "分类", "语种", "收藏", "历史"),
            headerTabs.map { resourceString(it) },
        )
    }

    @Test
    fun dashboardFooterShowsRealPlaybackProgressWithoutChangingPlayback() {
        val footer = parse("src/main/res/layout/view_ktv_bottom_bar.xml")
        val progress = (0 until footer.getElementsByTagName("ProgressBar").length)
            .map { footer.getElementsByTagName("ProgressBar").item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/playbackProgress" }

        assertEquals("1000", progress.getAttribute("android:max"))
        assertEquals(0, calculatePlaybackProgress(0L, 0L))
        assertEquals(500, calculatePlaybackProgress(30_000L, 60_000L))
        assertEquals(1000, calculatePlaybackProgress(80_000L, 60_000L))
        assertEquals(0, calculatePlaybackProgress(-1L, 60_000L))
        val bottomBar = locate("src/main/java/com/homektv/tv/ui/KtvBottomBarView.kt").readText()
        assertTrue("bottom bar must bind normalized position and duration to its progress view", bottomBar.contains(
            "binding.playbackProgress.progress = calculatePlaybackProgress(positionMs, durationMs)",
        ))
        val controller = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue("snapshot position and song duration must be passed through unchanged", controller.contains(
            "positionMs = snapshot.positionMs",
        ) && controller.contains("durationMs = snapshot.playing?.song?.durationMs?.toLong() ?: 0L"))
    }

    @Test
    fun footerAvatarUsesRealBitmapAndApi26CompatibleCircularClipping() {
        val footer = parse("src/main/res/layout/view_ktv_bottom_bar.xml")
        val avatar = (0 until footer.getElementsByTagName("ImageView").length)
            .map { footer.getElementsByTagName("ImageView").item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/imgNowPlayingDisc" }
        assertEquals("@drawable/bg_avatar_circle", avatar.getAttribute("android:background"))
        assertTrue("use the runtime API instead of the API-31-only XML attribute", !avatar.hasAttribute("android:clipToOutline"))
        val bottomBar = locate("src/main/java/com/homektv/tv/ui/KtvBottomBarView.kt").readText()
        assertTrue(bottomBar.contains("binding.imgNowPlayingDisc.clipToOutline = true"))
    }

    private fun parse(relativePath: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(locate(relativePath))

    private fun resourceString(reference: String): String {
        val name = reference.substringAfter("@string/")
        val strings = parse("src/main/res/values/strings.xml").getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) as Element }
            .single { it.getAttribute("name") == name }
            .textContent.trim()
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
