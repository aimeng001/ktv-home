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
        assertTrue(
            controller.contains("overlay.kioskNavTabs.visibility = View.VISIBLE") ||
                controller.contains("overlay.kioskNavTabs.visibility = if (isThreeColumnTab) View.GONE else View.VISIBLE")
        )
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
    fun kioskNavigationTextUsesReadableFocusColorAndKeepsGoldSelection() {
        val items = children(parse("src/main/res/color/ktv_kiosk_nav_text.xml"), "item")

        assertEquals(3, items.size)
        assertEquals("true", items[0].getAttribute("android:state_focused"))
        assertEquals("@color/ktv_screen_bg", items[0].getAttribute("android:color"))
        assertEquals("true", items[1].getAttribute("android:state_selected"))
        assertEquals("@color/ktv_dashboard_gold", items[1].getAttribute("android:color"))
        assertEquals("@color/dim", items[2].getAttribute("android:color"))

        val controller = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertEquals(2, Regex("setTextColor\\(ContextCompat.getColorStateList\\(activity, R.color.ktv_kiosk_nav_text\\)\\)")
            .findAll(controller).count())
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
            "tileNewSongs" to "@id/btnDockToneUp",
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
    fun bottomDockQueueButtonOpensTheQueueDrawer() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val queueClick = source.substringAfter("bottomBar.onQueueClick = {")
            .substringBefore("bottomBar.onQrCodeClick = {")

        val dock = parse("src/main/res/layout/view_ktv_bottom_dock.xml")
        assertEquals(1, children(dock, "*").count { it.getAttribute("android:id") == "@+id/btnDockQueue" })
        assertTrue(queueClick.contains("openQueueDrawer()"))
        assertEquals(1, Regex("KtvQueueDrawerDialog\\(").findAll(source).count())
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

        assertTrue(openDrawer.contains("val keyboardWasVisible = overlay.kioskKeyboard.visibility == View.VISIBLE"))
        assertTrue(openDrawer.contains("overlay.kioskKeyboard.visibility = View.INVISIBLE"))
        assertTrue(openDrawer.contains("if (keyboardWasVisible && presentationState.currentTab.value == KioskTab.PINYIN)"))
        assertTrue(openDrawer.contains("overlay.kioskKeyboard.visibility = View.VISIBLE"))
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
        assertTrue("the image scale mode preserves the split-art seam without independent crop", dashboardView.contains("ImageView.ScaleType.FIT_XY"))
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
