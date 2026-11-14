package com.homektv.tv.ui.kiosk

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvDashboardReferenceFidelityContractTest {
    @Test
    fun dashboardSharesOneHomeStageAcrossHeaderAndContent() {
        val activityRoot = parse("src/main/res/layout/activity_main.xml").documentElement
        assertEquals("@drawable/ktv_dashboard_background", activityRoot.getAttribute("android:background"))

        val root = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml").documentElement
        assertEquals("@android:color/transparent", root.getAttribute("android:background"))
        val mainActivity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue("stage background must span the toolbar and page content", mainActivity.contains("KtvDashboardBackground.applyTo(binding.root)"))

        val drawable = locate("src/main/res/drawable/ktv_dashboard_background.xml").readText()
        assertTrue("dashboard fallback must preserve the Home KTV vertical graphite fade", drawable.contains("<gradient"))
        assertEquals(
            listOf("#222226", "#1E1F24", "#191B1D", "#121718", "#0C0E12", "#0A0B0D", "#0A0B0D"),
            KtvDashboardBackground.fallbackStops.map { it.colorHex },
        )
        assertEquals(listOf(0.000f, 0.085f, 0.213f, 0.426f, 0.638f, 0.851f, 1.000f),
            KtvDashboardBackground.fallbackStops.map { it.position })
    }

    @Test
    fun visibleDashboardLabelsDescribeHomeKtvCapabilities() {
        assertEquals(
            listOf("热歌新歌", "精选专区", "歌手点歌", "歌名点歌", "语种点歌", "主题点歌", "综艺点歌", "抖音神曲", "我的收藏", "本地媒体"),
            KtvDashboardTile.dashboardEntries.map { it.title },
        )

        val dashboard = parse("src/main/res/layout/view_ktv_bento_dashboard.xml")
        val titleIds = listOf(
            "txtHotRankingTitle", "txtNewSongsTitle", "txtSingerTileTitle", "txtSongNameTitle",
            "txtLanguageTitle", "txtThemeTitle", "txtOperaTitle", "txtTikTokTitle",
            "txtFavoritesTitle", "txtLocalTitle",
        )
        titleIds.forEach { id ->
            val title = (0 until dashboard.getElementsByTagName("TextView").length)
                .map { dashboard.getElementsByTagName("TextView").item(it) as Element }
                .single { it.getAttribute("android:id") == "@+id/$id" }
            val tile = title.parentNode as Element
            assertTrue("$id must remain on the active dashboard", tile.getAttribute("android:visibility") != "gone")
        }

        val dashboardTextViews = (0 until dashboard.getElementsByTagName("TextView").length)
            .map { dashboard.getElementsByTagName("TextView").item(it) as Element }
            .associateBy { it.getAttribute("android:id") }
        listOf("@+id/txtSingerTileTitle", "@+id/txtSongNameTitle", "@+id/txtNewSongsTitle").forEach { id ->
            assertEquals(
                "$id must use match_parent so italic right glyph overhang is never clipped",
                "match_parent",
                dashboardTextViews.getValue(id).getAttribute("android:layout_width"),
            )
        }
        val dashboardViewSource = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvCommercialDashboardView.kt").readText()
        assertTrue(
            "wrap_content italic dashboard captions must expand minWidth for right glyph overhang",
            dashboardViewSource.contains("content.title.minWidth = textWidthPx"),
        )
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
