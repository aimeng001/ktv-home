package com.homektv.tv.ui.kiosk

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun bentoTilesExposeAReadableLabelAndAccessibleAction() {
        val document = parse("src/main/res/layout/view_ktv_bento_dashboard.xml")
        val elements = children(document, "*").associateBy { it.getAttribute("android:id") }
        val tileIds = listOf(
            "tileHotRanking", "tileNewSongs", "tileSinger", "tileSongName", "tileLanguage",
            "tileTheme", "tileOpera", "tileTikTok", "tileFavorites", "tileLocal",
        )

        tileIds.forEach { id ->
            val tile = elements.getValue("@+id/$id")
            assertTrue("$id must provide a spoken action label", tile.getAttribute("android:contentDescription").isNotBlank())
        }
        assertTrue(KtvDashboardTile.dashboardEntries.all { it.artworkRes != 0 })
    }

    @Test
    fun openingSearchFromDashboardPlacesFocusOnSearchNavigation() {
        val controller = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val searchRoute = controller.substringAfter("KtvDashboardTile.SONG_NAME ->")
            .substringBefore("KtvDashboardTile.LOCAL ->")

        assertTrue(searchRoute.contains("openCommercialRoute(KioskCommercialRoute.SONG_NAME)"))
        assertTrue(searchRoute.contains("browserBinding.btnTabSongName.post"))
        assertTrue(searchRoute.contains("browserBinding.btnTabSongName.requestFocus()"))
    }

    @Test
    fun transientFeedbackFloatsWithoutReservingAFullWidthBannerRow() {
        val document = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val byId = children(document, "*").associateBy { it.getAttribute("android:id") }
        val banner = byId.getValue("@+id/kioskTransientFeedback")
        val content = byId.getValue("@+id/kioskMainContent")

        assertEquals("false", banner.getAttribute("android:focusable"))
        assertEquals("false", banner.getAttribute("android:clickable"))
        assertEquals("polite", banner.getAttribute("android:accessibilityLiveRegion"))
        assertEquals("wrap_content", banner.getAttribute("android:layout_width"))
        assertEquals("@drawable/bg_ktv_transient_toast", banner.getAttribute("android:background"))
        assertEquals("parent", content.getAttribute("app:layout_constraintTop_toTopOf"))
        assertEquals("parent", banner.getAttribute("app:layout_constraintEnd_toEndOf"))

        val controller = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(controller.contains("transientFeedbackHost.clear()"))
        assertTrue(controller.contains("fun serviceStatusSummary(): String"))
        val mainActivity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue(mainActivity.contains("\"服务状态: \" + serviceStatus"))
    }

    @Test
    fun dashboardUsesTenFocusLinkedTilesInAnAsymmetricMetroGrid() {
        val document = parse("src/main/res/layout/view_ktv_bento_dashboard.xml")
        val root = document.documentElement
        val elements = children(document, "*").associateBy { it.getAttribute("android:id") }
        val tileIds = listOf(
            "tileHotRanking", "tileNewSongs", "tileSinger", "tileSongName", "tileLanguage",
            "tileTheme", "tileOpera", "tileTikTok", "tileFavorites", "tileLocal",
        )

        assertEquals("androidx.constraintlayout.widget.ConstraintLayout", root.tagName)
        assertEquals("0.189", elements.getValue("@+id/bentoColumnOneEnd").getAttribute("app:layout_constraintGuide_percent"))
        assertEquals("0.500", elements.getValue("@+id/bentoColumnTwoEnd").getAttribute("app:layout_constraintGuide_percent"))
        assertEquals("0.688", elements.getValue("@+id/bentoColumnThreeEnd").getAttribute("app:layout_constraintGuide_percent"))
        assertEquals("0.3333", elements.getValue("@+id/bentoOneThirdHeight").getAttribute("app:layout_constraintGuide_percent"))
        assertEquals("0.6667", elements.getValue("@+id/bentoTwoThirdHeight").getAttribute("app:layout_constraintGuide_percent"))
        tileIds.forEach { id ->
            val tile = elements.getValue("@+id/$id")
            assertEquals("0dp", tile.getAttribute("android:layout_width"))
            assertEquals("0dp", tile.getAttribute("android:layout_height"))
            assertEquals("true", tile.getAttribute("android:focusable"))
            assertEquals("true", tile.getAttribute("android:clickable"))
            listOf("Left", "Right", "Up", "Down").forEach { direction ->
                assertTrue(tile.getAttribute("android:nextFocus$direction").startsWith("@id/"))
            }
        }
        assertEquals("@id/tileOpera", elements.getValue("@+id/tileSinger").getAttribute("android:nextFocusRight"))
        assertEquals("@id/tileSongName", elements.getValue("@+id/tileSinger").getAttribute("android:nextFocusDown"))
        val expectedDownTargets = mapOf(
            "tileTikTok" to "@id/btnDockVocal",
            "tileSongName" to "@id/btnDockVolDown",
            "tileTheme" to "@id/video_container",
            "tileNewSongs" to "@id/tileFavorites",
            "tileFavorites" to "@id/btnDockToneDown",
            "tileLocal" to "@id/btnDockDownloads",
        )
        expectedDownTargets.forEach { (tileId, targetId) ->
            assertEquals(targetId, elements.getValue("@+id/$tileId").getAttribute("android:nextFocusDown"))
        }
        assertTrue("tile down links must not target hidden legacy dock nodes", elements.values.none {
            it.getAttribute("android:nextFocusDown") in setOf(
                "@id/btnDockHome", "@id/btnDockHistory", "@id/btnDockMixer", "@id/btnDockCheer",
            )
        })
        assertTrue("only runtime child artwork rotates; tile layout IDs stay fixed", elements.values.none { it.tagName == "ImageSwitcher" })

        val dashboardView = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvCommercialDashboardView.kt").readText()
        assertTrue(dashboardView.contains("content.root.background = createRoundedClipSurface()"))
        assertTrue(dashboardView.contains("content.root.foreground = createFocusOverlay()"))
        assertTrue(dashboardView.contains("tile.artworkRes"))
    }

    @Test
    fun bottomDockQueueButtonOpensTheQueueDrawer() {
        val controllerSource = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val dockSource = locate("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").readText()
        val mainSource = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()

        val dock = parse("src/main/res/layout/view_ktv_bottom_dock.xml")
        assertEquals(1, children(dock, "*").count { it.getAttribute("android:id") == "@+id/btnDockQueue" })
        assertTrue(dockSource.contains("binding.btnDockQueue.setOnClickListener { actions.onQueue() }"))
        assertTrue(mainSource.contains("onQueue = ::showQueueOverlay"))
        assertEquals(1, Regex("KtvQueueDrawerDialog\\(").findAll(controllerSource).count())
    }

    @Test
    fun rootDockQueueButtonUsesDrawerForKioskModesEvenWhenKioskIsInactive() {
        val source = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val queueAction = source.substringAfter("private fun showQueueOverlay(")
            .substringBefore("private fun showHomePage()")

        assertTrue(queueAction.contains("if (modeCapabilities.canOpenKiosk)"))
        assertTrue(queueAction.contains("ensureKioskController().openQueueDrawer(onDismiss)"))
        assertTrue(queueAction.indexOf("ensureKioskController().openQueueDrawer(onDismiss)") <
            queueAction.indexOf("binding.queueOverlay.visibility = View.VISIBLE"))
        assertTrue("non-kiosk device modes must retain the existing queue page",
            queueAction.contains("binding.queueOverlay.visibility = View.VISIBLE"))
    }

    @Test
    fun queueDrawerTemporarilyHidesSearchKeyboardAndRestoresItWithoutClearing() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val openDrawer = source.substringAfter("fun openQueueDrawer(")
            .substringBefore("fun setCachedQrBitmap")

        assertTrue(openDrawer.contains("if (queueDialog?.isShowing == true) return"))
        assertTrue(openDrawer.contains("val keyboardWasVisible = overlay.kioskKeyboard.visibility == View.VISIBLE"))
        assertTrue(openDrawer.contains("overlay.kioskKeyboard.visibility = View.INVISIBLE"))
        assertTrue(openDrawer.contains("if (keyboardWasVisible && isSearchKeyboardShowing())"))
        assertTrue(openDrawer.contains("overlay.kioskKeyboard.visibility = View.VISIBLE"))
        assertTrue(openDrawer.contains("applyKeyboardVisibility(isSearchKeyboardShowing())"))
        assertTrue(openDrawer.contains("overlay.kioskKeyboard.setKeyword" ).not())
        assertTrue(openDrawer.contains("overlay.kioskKeyboard.clear()").not())
    }

    @Test
    fun queueDrawerDoesNotReparentTheGlobalDockPlayer() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val openDrawer = source.substringAfter("fun openQueueDrawer(")
            .substringBefore("fun setCachedQrBitmap")

        val activeTransition = source.substringAfter("private fun applyKioskActive(active: Boolean)")
            .substringBefore("private fun renderPipState()")
        assertTrue("the queue drawer should still open from the dock", openDrawer.contains("dialog.show()"))
        assertTrue("the playing view should stay owned by the global dock", activeTransition.contains("bottomDockController.ensurePlayerInDock()"))
        assertTrue("the same view should not be reparented for the queue drawer", !openDrawer.contains("reparentView("))
    }

    @Test
    fun bentoTilesUseReferenceArtworkWithoutBlackCaptionBars() {
        val content = locate("src/main/res/layout/view_ktv_bento_dashboard.xml").readText()
        assertFalse("旧半透明黑色标题条必须从大厅移除", content.contains("@drawable/bg_ktv_dashboard_tile_caption"))
        assertFalse("旧居中图标水印不能盖住参考原画", content.contains("android:alpha=\"0.46\""))

        val dashboardView = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvCommercialDashboardView.kt").readText()
        assertTrue("each tile must load the source art recorded by its semantic tile", dashboardView.contains("tile.artworkRes"))
        assertTrue("焦点状态应使用 IKTV 50 原版单层高亮蓝框", dashboardView.contains("R.color.ktv_focus_blue") && dashboardView.contains("setStroke(focusStroke, focusColor)"))
    }

    @Test
    fun singerAndSongNameTilesBindTheMatchedFixedReferencePair() {
        val tileSource = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvDashboardTile.kt").readText()
        assertTrue(tileSource.contains("SINGER(\"歌手点歌\", \"按歌手浏览曲库\", R.drawable.ktv_ref_v50_tile_singers_01)"))
        assertTrue(tileSource.contains("SONG_NAME(\"歌名点歌\", \"拼音与首字母搜索\", R.drawable.ktv_ref_v50_tile_songs_01)"))
        val dashboardView = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvCommercialDashboardView.kt").readText()
        assertTrue("the remaining eight tiles use their fixed source art", dashboardView.contains("setImageResource(tile.artworkRes)"))
        assertTrue("photo tiles must draw their focus border over child artwork", dashboardView.contains("content.root.foreground = createFocusOverlay()"))
        assertTrue("the two photo tiles share three matched pairs", dashboardView.contains("photoPairs = listOf(") &&
            dashboardView.contains("R.drawable.ktv_ref_v50_tile_singers_03") &&
            dashboardView.contains("R.drawable.ktv_ref_v50_tile_songs_03"))
        assertTrue("the image scale mode avoids FIT_XY stretching across tiles", dashboardView.contains("ImageView.ScaleType.CENTER_CROP"))
        assertEquals(10_000L, KtvDashboardPhotoCarousel.INTERVAL_MS)
    }

    private fun elementChildren(parent: Element): List<Element> = (0 until parent.childNodes.length)
        .mapNotNull { parent.childNodes.item(it) as? Element }

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
