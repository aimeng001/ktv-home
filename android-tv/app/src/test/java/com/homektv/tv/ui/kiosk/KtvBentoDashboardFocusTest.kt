package com.homektv.tv.ui.kiosk

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvBentoDashboardFocusTest {
    private val tileIds = listOf(
        "tileHotRanking",
        "tileNewSongs",
        "tileSinger",
        "tileSongName",
        "tileLanguage",
        "tileTheme",
        "tileOpera",
        "tileTikTok",
        "tileFavorites",
        "tileLocal",
    )

    @Test
    fun dashboardDefinesTheTenCommercialKtvTilesInVisualOrder() {
        assertEquals(
            listOf(
                KtvDashboardTile.HOT_RANKING,
                KtvDashboardTile.NEW_SONGS,
                KtvDashboardTile.SINGER,
                KtvDashboardTile.SONG_NAME,
                KtvDashboardTile.LANGUAGE,
                KtvDashboardTile.THEME,
                KtvDashboardTile.OPERA,
                KtvDashboardTile.TIKTOK,
                KtvDashboardTile.FAVORITES,
                KtvDashboardTile.LOCAL,
            ),
            KtvDashboardTile.dashboardEntries,
        )
        assertEquals(tileIds.size, KtvDashboardTile.dashboardEntries.size)
    }

    @Test
    fun allTilesDeclareFourDirectionalFocusTargetsAndFormAReachableGraph() {
        val document = dashboardDocument()
        val elements = allElements(document)
        val byId = elements.associateBy { it.getAttribute("android:id") }
        val tileElements = tileIds.associateWith { id -> byId.getValue("@+id/$id") }
        val directions = listOf("Left", "Right", "Up", "Down")
        val visibleDockTargets = setOf(
            "btnDockPlayPause", "btnDockVocal", "btnDockRestart", "btnDockNext", "btnDockVolDown",
            "video_container", "btnDockVolUp", "btnDockToneDown", "btnDockToneUp", "btnDockDownloads", "btnDockQueue",
        )
        val hiddenLegacyTargets = setOf("btnDockHome", "btnDockHistory", "btnDockMixer", "btnDockCheer")
        val allDashboardIds = tileIds.toSet() + "tabDashboard" + visibleDockTargets

        tileElements.forEach { (id, tile) ->
            directions.forEach { direction ->
                val target = tile.getAttribute("android:nextFocus$direction")
                assertTrue("$id must declare nextFocus$direction", target.startsWith("@id/"))
                assertTrue(
                    "$id nextFocus$direction must resolve to a dashboard or dock control",
                    target.removePrefix("@id/") in allDashboardIds,
                )
                assertTrue(
                    "$id nextFocus$direction must not point at a hidden legacy dock node",
                    target.removePrefix("@id/") !in hiddenLegacyTargets,
                )
            }
        }

        val dockTargets = allElements(
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(locate("src/main/res/layout/view_ktv_bottom_dock.xml")),
        ).filter { element ->
            element.getAttribute("android:focusable") == "true" &&
                element.getAttribute("android:visibility") != "gone"
        }.map { it.getAttribute("android:id").removePrefix("@+id/") }.toSet()
        assertTrue("every dashboard dock focus target must remain visible and focusable", dockTargets.containsAll(visibleDockTargets))

        val visited = linkedSetOf("tileHotRanking")
        val pending = ArrayDeque<String>().apply { add("tileHotRanking") }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            directions.forEach { direction ->
                val target = tileElements.getValue(id).getAttribute("android:nextFocus$direction")
                    .removePrefix("@id/")
                if (target in tileIds && visited.add(target)) pending.add(target)
            }
        }

        assertEquals(tileIds.toSet(), visited)
    }

    @Test
    fun spatialFocusFollowsTheReferenceGridAndLowerTilesEnterTheDock() {
        val byId = allElements(dashboardDocument()).associateBy { it.getAttribute("android:id") }
        assertEquals("@id/tileOpera", byId.getValue("@+id/tileSinger").getAttribute("android:nextFocusRight"))
        assertEquals("@id/tileSongName", byId.getValue("@+id/tileSinger").getAttribute("android:nextFocusDown"))
        assertEquals("@id/btnDockToneUp", byId.getValue("@+id/tileNewSongs").getAttribute("android:nextFocusDown"))
        assertEquals("@id/btnDockVolDown", byId.getValue("@+id/tileSongName").getAttribute("android:nextFocusDown"))
        assertEquals("@id/video_container", byId.getValue("@+id/tileTheme").getAttribute("android:nextFocusDown"))
        assertEquals("@id/btnDockToneDown", byId.getValue("@+id/tileFavorites").getAttribute("android:nextFocusDown"))
        assertEquals("@id/btnDockDownloads", byId.getValue("@+id/tileLocal").getAttribute("android:nextFocusDown"))
    }

    @Test
    fun everyTileKeepsRemoteFocusAndUsesFixedReferenceArtwork() {
        val byId = allElements(dashboardDocument()).associateBy { it.getAttribute("android:id") }

        tileIds.forEach { id ->
            val tile = byId["@+id/$id"]
            assertNotNull("missing @+id/$id", tile)
            assertEquals("true", tile?.getAttribute("android:focusable"))
            assertEquals("true", tile?.getAttribute("android:clickable"))
            assertEquals("@animator/ktv_card_focus_scale", tile?.getAttribute("android:stateListAnimator"))
        }
        val tileSource = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvDashboardTile.kt").readText()
        listOf(
            "ktv_ref_v50_tile_hot_new", "ktv_ref_v50_tile_featured", "ktv_ref_v50_tile_singers_01",
            "ktv_ref_v50_tile_songs_01", "ktv_ref_v50_tile_variety", "ktv_ref_v50_tile_language",
            "ktv_ref_v50_tile_topic", "ktv_ref_v50_tile_tiktok", "ktv_ref_v50_tile_favorite",
            "ktv_ref_v50_tile_local",
        ).forEach { asset -> assertTrue("dashboard must bind $asset", tileSource.contains(asset)) }
        val viewSource = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvCommercialDashboardView.kt").readText()
        assertTrue(viewSource.contains("tile == KtvDashboardTile.SINGER || tile == KtvDashboardTile.SONG_NAME"))
        assertTrue(viewSource.contains("ImageSwitcher(context)"))
        assertTrue(viewSource.contains("content.root.foreground = createFocusOverlay()"))
    }

    private fun dashboardDocument() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(locate("src/main/res/layout/view_ktv_bento_dashboard.xml"))

    private fun allElements(document: org.w3c.dom.Document): List<Element> {
        val elements = document.getElementsByTagName("*")
        return (0 until elements.length).map { elements.item(it) as Element }
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../android-tv/app/$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
