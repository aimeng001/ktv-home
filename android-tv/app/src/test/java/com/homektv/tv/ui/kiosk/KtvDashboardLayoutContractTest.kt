package com.homektv.tv.ui.kiosk

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvDashboardLayoutContractTest {
    @Test
    fun kioskOverlayKeepsDpadFocusOnInteractiveDescendants() {
        val root = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml").documentElement

        assertEquals("false", root.getAttribute("android:focusable"))
        assertEquals("afterDescendants", root.getAttribute("android:descendantFocusability"))
    }

    @Test
    fun dashboardTileShowsGoldSemanticIconAboveCenteredLabels() {
        val document = parse("src/main/res/layout/item_kiosk_dashboard_tile.xml")
        val icons = document.getElementsByTagName("ImageView")

        assertEquals("each visible home tile needs an icon slot", 1, icons.length)
        val icon = icons.item(0) as Element
        assertEquals("@+id/imgTileIcon", icon.getAttribute("android:id"))
        assertEquals("@color/ktv_dashboard_gold", icon.getAttribute("app:tint"))
        assertEquals("center", document.documentElement.getAttribute("android:gravity"))
        assertTrue(KtvDashboardTile.dashboardEntries.all { it.iconRes != 0 })

        val title = children(document, "TextView").first { it.getAttribute("android:id") == "@+id/txtTileTitle" }
        assertEquals("center", title.getAttribute("android:gravity"))
    }

    @Test
    fun dashboardKeepsSharedTopNavigationWithFocusedAndSelectedStates() {
        val document = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val navigationIds = setOf(
            "@+id/tabDashboard",
            "@+id/tabPinyin",
            "@+id/tabSingers",
            "@+id/tabCategories",
            "@+id/tabLanguages",
            "@+id/tabFavorites",
            "@+id/tabHistory",
        )
        val buttons = children(document, "Button").associateBy { it.getAttribute("android:id") }
        navigationIds.forEach { id ->
            assertEquals(id, "@drawable/bg_kiosk_nav_tab", buttons.getValue(id).getAttribute("android:background"))
        }
        assertTrue(parse("src/main/res/drawable/bg_kiosk_nav_tab.xml").documentElement.tagName == "selector")

        val controller = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(controller.contains("overlay.kioskNavTabs.visibility = View.VISIBLE"))
    }

    @Test
    fun dashboardUsesSevenVisibleTilesInCenteredFourPlusThreeGrid() {
        val document = parse("src/main/res/layout/view_ktv_commercial_dashboard.xml")
        val root = document.documentElement
        val tiles = children(document, "include")
            .filter { it.getAttribute("layout") == "@layout/item_kiosk_dashboard_tile" }
        val visible = tiles.filter { it.getAttribute("android:visibility") != "gone" }
        val byId = tiles.associateBy { it.getAttribute("android:id") }
        val elements = children(document, "*").associateBy { it.getAttribute("android:id") }
        val topRow = elements.getValue("@+id/dashboardTopRow")
        val bottomRow = elements.getValue("@+id/dashboardBottomRow")

        assertEquals("androidx.constraintlayout.widget.ConstraintLayout", root.tagName)
        assertEquals(7, visible.size)
        assertEquals("horizontal", topRow.getAttribute("android:orientation"))
        assertEquals("horizontal", bottomRow.getAttribute("android:orientation"))
        assertEquals("false", topRow.getAttribute("android:baselineAligned"))
        assertEquals("false", bottomRow.getAttribute("android:baselineAligned"))
        assertEquals("0.835", bottomRow.getAttribute("app:layout_constraintWidth_percent"))
        assertEquals("0.5", bottomRow.getAttribute("app:layout_constraintHorizontal_bias"))
        assertEquals("5dp", topRow.getAttribute("android:layout_marginBottom"))
        assertEquals("5dp", bottomRow.getAttribute("android:layout_marginTop"))
        assertEquals("2dp", bottomRow.getAttribute("android:layout_marginBottom"))
        assertEquals(
            listOf("@+id/tilePinyin", "@+id/tileSinger", "@+id/tileCategory", "@+id/tileLanguage"),
            directIncludes(topRow).map { it.getAttribute("android:id") },
        )
        assertEquals(
            listOf("@+id/tileFavorites", "@+id/tileHistory", "@+id/tileQueue"),
            directIncludes(bottomRow).map { it.getAttribute("android:id") },
        )
        visible.forEach { tile ->
            assertEquals("0dp", tile.getAttribute("android:layout_width"))
            assertEquals("1", tile.getAttribute("android:layout_weight"))
        }
        assertTrue("legacy ranking tile must not be present", !byId.containsKey("@+id/tileRanking"))

        val dashboardView = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvCommercialDashboardView.kt")
            .readText()
            .substringAfter("private fun configureTileFocusLinks()")
            .substringBefore("\n    }")
        listOf(
            "nextFocusRightId = R.id.tileSinger",
            "nextFocusDownId = R.id.tileFavorites",
            "nextFocusLeftId = R.id.tilePinyin",
            "nextFocusRightId = R.id.tileCategory",
            "nextFocusLeftId = R.id.tileSinger",
            "nextFocusRightId = R.id.tileLanguage",
            "nextFocusDownId = R.id.tileHistory",
            "nextFocusLeftId = R.id.tileCategory",
            "nextFocusDownId = R.id.tileQueue",
            "nextFocusUpId = R.id.tilePinyin",
            "nextFocusRightId = R.id.tileHistory",
            "nextFocusDownId = R.id.btnPlayPause",
            "nextFocusUpId = R.id.tileCategory",
            "nextFocusRightId = R.id.tileQueue",
            "nextFocusUpId = R.id.tileLanguage",
            "nextFocusLeftId = R.id.tileHistory",
        ).forEach { link ->
            assertTrue("focus link must be applied to the included tile root: $link", dashboardView.contains(link))
        }
        assertEquals(3, Regex("nextFocusDownId = R.id.btnPlayPause").findAll(dashboardView).count())
    }

    @Test
    fun topNavigationHidesRankingAndPlaylistsAndAddsLanguageEntry() {
        val document = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val elements = children(document, "Button").associateBy { it.getAttribute("android:id") }

        assertEquals("gone", elements.getValue("@+id/tabRankings").getAttribute("android:visibility"))
        assertEquals("gone", elements.getValue("@+id/tabPlaylists").getAttribute("android:visibility"))
        assertEquals("@id/tabLanguages", elements.getValue("@+id/tabCategories").getAttribute("android:nextFocusRight"))
        assertTrue(elements.containsKey("@+id/tabLanguages"))
    }

    @Test
    fun categoryPickerDoesNotExposeNewSongOrRankingContent() {
        val document = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val elements = children(document, "Button").associateBy { it.getAttribute("android:id") }

        assertEquals("gone", elements.getValue("@+id/btnCategoryNew").getAttribute("android:visibility"))
        assertEquals("false", elements.getValue("@+id/btnCategoryNew").getAttribute("android:focusable"))
    }

    @Test
    fun dashboardAndBottomBarOpenTheSameQueueDrawer() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val queueClick = source.substringAfter("bottomBar.onQueueClick = {")
            .substringBefore("bottomBar.onQrCodeClick = {")

        assertTrue(source.contains("KtvDashboardTile.ORDERED_QUEUE -> openQueueDrawer()"))
        assertTrue(queueClick.contains("openQueueDrawer()"))
        assertEquals(1, Regex("KtvQueueDrawerDialog\\(").findAll(source).count())
    }

    private fun directIncludes(parent: Element): List<Element> = (0 until parent.childNodes.length)
        .mapNotNull { parent.childNodes.item(it) as? Element }
        .filter { it.tagName == "include" }

    private fun children(document: org.w3c.dom.Document, tag: String): List<Element> {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
    }

    private fun parse(relativePath: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(locate(relativePath))

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val candidates = sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
