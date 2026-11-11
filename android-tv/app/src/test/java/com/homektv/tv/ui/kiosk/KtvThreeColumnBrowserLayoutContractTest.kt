package com.homektv.tv.ui.kiosk

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvThreeColumnBrowserLayoutContractTest {
    @Test
    fun centerColumnCollapsesDynamicallyBasedOnRealContent() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val renderMethod = source.substringAfter("private fun renderThreeColumnBrowser")
            .substringBefore("private fun isThreeColumnTab")

        assertTrue(
            "中心列必须基于实际内容（键盘、歌手面板、筛选列表及重试项）动态折叠，避免过宽中栏留白",
            renderMethod.contains("browser.browserCenterColumn.visibility"),
        )
        assertTrue(
            "当中心列只有无焦点的静态提示时应折叠，但出现重试选项时必须保留",
            renderMethod.contains("browserFilterOptions.childCount") || renderMethod.contains("hasFilterOptions"),
        )
    }

    @Test
    fun keyboardVisibilityFollowsGlobalSearchPreferenceAndSkipsVarietyPanel() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val renderMethod = source.substringAfter("private fun renderThreeColumnBrowser")
            .substringBefore("private fun isThreeColumnTab")

        assertTrue(renderMethod.contains("KtvGlobalSearchPolicy.shouldShowKeyboard("))
        assertTrue(renderMethod.contains("isVariety = isVariety"))
        assertTrue(renderMethod.contains("enabled = globalSearchEnabled()"))
        assertTrue(renderMethod.contains("browser.browserFilterScroll.visibility = View.GONE"))
        assertTrue(renderMethod.contains("browser.txtBrowserCenterHint.visibility = View.GONE"))
        assertTrue(renderMethod.contains("browser.browserSingerRecyclerView.nextFocusRightId"))
        assertTrue(renderMethod.contains("kioskKeyboard.setContentFocusTarget(keyboardContentTarget.id)"))
    }

    @Test
    fun capsuleLabelsRetainAllElevenCommercialDestinations() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_three_column_browser.xml"))
        val buttons = (0 until document.getElementsByTagName("Button").length)
            .map { document.getElementsByTagName("Button").item(it) as org.w3c.dom.Element }
            .associateBy { it.getAttribute("android:id") }
        val expected = mapOf(
            "btnTabSongName" to "歌名点歌",
            "btnTabHd" to "高清专区",
            "btnTabSingers" to "歌手点歌",
            "btnTabHotNew" to "热歌新歌",
            "btnTabFeatured" to "精选专区",
            "btnTabLocal" to "本地媒体",
            "btnTabFavorites" to "我的收藏",
            "btnTabLanguages" to "语种点歌",
            "btnTabTopics" to "主题点歌",
            "btnTabVariety" to "综艺点歌",
            "btnTabTikTok" to "抖音神曲",
        )
        expected.forEach { (id, label) ->
            assertTrue("$id must use Home KTV label $label", buttons.getValue("@+id/$id").getAttribute("android:text") == label)
        }

        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(source.contains("browserBinding.btnTabTikTok.setOnClickListener"))
        assertTrue(source.contains("btnTabTikTok.setOnClickListener { openCommercialRoute(KioskCommercialRoute.TIKTOK) }"))
    }

    @Test
    fun browserGeometryMatchesTheReferenceCompositionAt1080p() {
        val browser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_three_column_browser.xml"))
        val browserNodes = (0 until browser.getElementsByTagName("*").length)
            .map { browser.getElementsByTagName("*").item(it) as Element }
            .associateBy { it.getAttribute("android:id") }
        val capsuleScroll = browserNodes.getValue("@+id/browserCapsuleScroll")
        val subcategoryRail = browserNodes.getValue("@+id/browserSubCategoryRail")
        val mainStage = browserNodes.getValue("@+id/browserMainStage")
        val tabs = listOf(
            "btnTabSongName", "btnTabHd", "btnTabSingers", "btnTabHotNew", "btnTabFeatured",
            "btnTabLocal", "btnTabFavorites", "btnTabLanguages", "btnTabTopics", "btnTabVariety", "btnTabTikTok",
        ).map { browserNodes.getValue("@+id/$it") }

        val dimensionDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/values/dimens_ktv_dashboard.xml"))
        val dimensions = (0 until dimensionDocument.getElementsByTagName("dimen").length)
            .map { dimensionDocument.getElementsByTagName("dimen").item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }

        assertEquals("0dp", browser.documentElement.getAttribute("android:paddingHorizontal"))
        assertEquals("0dp", browser.documentElement.getAttribute("android:paddingTop"))
        assertEquals("6dp", browser.documentElement.getAttribute("android:paddingBottom"))
        assertEquals("@dimen/ktv_browser_capsule_height", capsuleScroll.getAttribute("android:layout_height"))
        assertEquals("28dp", dimensions["ktv_browser_capsule_height"])
        tabs.forEachIndexed { index, tab ->
            assertEquals("@dimen/ktv_browser_tab_height", tab.getAttribute("android:layout_height"))
            assertEquals("@dimen/ktv_browser_tab_horizontal_padding", tab.getAttribute("android:paddingHorizontal"))
            if (index < tabs.lastIndex) {
                assertEquals("@dimen/ktv_browser_tab_gap", tab.getAttribute("android:layout_marginEnd"))
            }
        }
        assertEquals("28dp", dimensions["ktv_browser_tab_height"])
        assertEquals("6dp", dimensions["ktv_browser_tab_horizontal_padding"])
        assertEquals("3dp", dimensions["ktv_browser_tab_gap"])
        assertEquals("24dp", subcategoryRail.getAttribute("android:layout_height"))
        assertEquals("2dp", subcategoryRail.getAttribute("android:layout_marginTop"))
        assertEquals("18dp", mainStage.getAttribute("android:layout_marginTop"))
        assertEquals("240dp", dimensions["ktv_browser_center_col_width"])

        val overlay = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_kiosk_overlay.xml"))
        val overlayNodes = (0 until overlay.getElementsByTagName("*").length)
            .map { overlay.getElementsByTagName("*").item(it) as Element }
            .associateBy { it.getAttribute("android:id") }
        val stage = overlayNodes.getValue("@+id/kioskContentStage")
        val dashboard = overlayNodes.getValue("@+id/kioskDashboardView")
        assertEquals("10dp", stage.getAttribute("android:paddingHorizontal"))
        assertEquals("0dp", stage.getAttribute("android:paddingTop"))
        assertEquals("14dp", dashboard.getAttribute("android:layout_marginStart"))
        assertEquals("14dp", dashboard.getAttribute("android:layout_marginEnd"))
        assertEquals("5dp", dashboard.getAttribute("android:layout_marginTop"))
        assertEquals("6dp", dashboard.getAttribute("android:layout_marginBottom"))
    }

    @Test
    fun horizontalDpadPathVisitsAllElevenCapsulesAndWrapsAtEnds() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_three_column_browser.xml"))
        val buttons = (0 until document.getElementsByTagName("Button").length)
            .map { document.getElementsByTagName("Button").item(it) as org.w3c.dom.Element }
            .associateBy { it.getAttribute("android:id") }
        val ids = listOf(
            "btnTabSongName", "btnTabHd", "btnTabSingers", "btnTabHotNew", "btnTabFeatured",
            "btnTabLocal", "btnTabFavorites", "btnTabLanguages", "btnTabTopics", "btnTabVariety", "btnTabTikTok",
        )

        ids.forEachIndexed { index, id ->
            val expectedLeft = ids.getOrElse(index - 1) { ids.last() }
            val expectedRight = ids.getOrElse(index + 1) { ids.first() }
            val button = buttons.getValue("@+id/$id")
            assertTrue("$id must move left to $expectedLeft", button.getAttribute("android:nextFocusLeft") == "@id/$expectedLeft")
            assertTrue("$id must move right to $expectedRight", button.getAttribute("android:nextFocusRight") == "@id/$expectedRight")
        }

        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val explicitPath = source.substringAfter("private fun bindCommercialCapsuleDpadNavigation()")
            .substringBefore("private fun observeState()")
        assertTrue(explicitPath.contains("KeyEvent.KEYCODE_DPAD_LEFT"))
        assertTrue(explicitPath.contains("KeyEvent.KEYCODE_DPAD_RIGHT"))
        assertTrue(explicitPath.contains("buttons[targetIndex].requestRemoteFocus()"))
        assertTrue(explicitPath.contains("event.repeatCount > 0"))
    }

    @Test
    fun aggregateBrowserEntryFocusTargetsVisibleControlsInsteadOfHiddenLegacyNavigation() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val target = source.substringAfter("private fun threeColumnFocusTarget(")
            .substringBefore("private fun showUnsupportedSingerRegion(")
        val selection = source.substringAfter("private fun applyTabSelection(")
            .substringBefore("private fun openCommercialRoute(")
        val activation = source.substringAfter("private fun applyKioskActive(")
            .substringBefore("private fun renderPipState(")

        assertTrue(target.contains("KioskTab.PINYIN -> KioskCommercialRoute.SONG_NAME"))
        assertTrue(target.contains("KioskTab.PINYIN -> browserBinding.btnTabSongName"))
        assertTrue(target.contains("KioskTab.SINGERS -> browserBinding.btnTabSingers"))
        assertTrue(target.contains("KioskTab.HISTORY -> browserBinding.browserResultsRecyclerView"))
        assertTrue(selection.contains("val focusTarget = threeColumnFocusTarget(tab)"))
        assertTrue(activation.contains("threeColumnFocusTarget(currentTab).requestFocus()"))
        assertFalse(selection.contains("browserBinding.browserNavSearch"))
        assertFalse(activation.contains("browserBinding.browserNavSearch"))
    }

    @Test
    fun globalSearchExplicitlyFocusesTheVisibleSongNameCapsule() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val openGlobalSearch = source.substringAfter("fun openGlobalSearch(")
            .substringBefore("fun selectTab(")

        assertTrue(openGlobalSearch.contains("selectTab(KioskTab.PINYIN, KioskCommercialRoute.SONG_NAME)"))
        assertTrue(openGlobalSearch.contains("toggleKiosk(true)"))
        assertTrue(openGlobalSearch.contains("browserBinding.btnTabSongName.post"))
        assertTrue(openGlobalSearch.contains("browserBinding.btnTabSongName.requestFocusFromTouch()"))
    }

    @Test
    fun commercialRouteChangesKeepCapsuleFocusAndFocusedSelector() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val renderMethod = source.substringAfter("// 同步 11 大主导航胶囊选中状态")
            .substringBefore("private fun renderAvailableVarietyPrograms(")
        val routeMethod = source.substringAfter("private fun openCommercialRoute(")
            .substringBefore("private fun openCommercialTagRoute(")
        val capsuleDpadMethod = source.substringAfter("private fun bindCommercialCapsuleDpadNavigation()")
            .substringBefore("private fun observeState()")
        val browserRenderMethod = source.substringAfter("private fun renderThreeColumnBrowser(")
            .substringBefore("private fun showVarietyPanel(")

        assertTrue(renderMethod.contains("btn.setBackgroundResource(R.drawable.bg_ktv_capsule_tab)"))
        assertFalse(renderMethod.contains("if (isSelected) R.drawable.bg_ktv_capsule_tab_active"))
        assertFalse(routeMethod.contains("button.requestFocusFromTouch()"))
        assertTrue(browserRenderMethod.contains("KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus("))
        assertTrue(browserRenderMethod.contains("routeActivationPending = routeActivationStillPending"))
        assertTrue(browserRenderMethod.contains("browserHadFocusBeforeRender = browserHadFocusBeforeRender"))
        assertTrue(browserRenderMethod.contains("pendingCommercialRouteFocus == routeAtRenderEnd"))
        assertTrue(browserRenderMethod.contains("noVisibleViewHasFocusBeforeRender"))
        assertTrue(browserRenderMethod.contains("routeActivationStillPending"))
        assertTrue(browserRenderMethod.contains("anotherVisibleActionHasFocus = anotherVisibleActionHasFocus"))
        assertTrue(browserRenderMethod.contains("noVisibleViewHasFocus = focusedView?.isShown != true"))
        assertTrue(capsuleDpadMethod.contains("buttons[targetIndex].requestRemoteFocus()"))
        assertFalse(capsuleDpadMethod.contains("buttons[targetIndex].requestFocus()"))
        assertTrue(browserRenderMethod.contains("routeFocusTarget.requestRemoteFocus()"))
        assertFalse(browserRenderMethod.contains("routeFocusTarget.requestFocus()"))
        assertTrue(routeMethod.contains("pendingCommercialRouteFocus = route"))
    }

    @Test
    fun songAndSingerPaginationUseTheirOwnListsAndServerTotals() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val paging = source.substringAfter("private fun scrollResultsPage(")
            .substringBefore("private fun updatePageIndicator(")
        val indicator = source.substringAfter("private fun updatePageIndicator(")
            .substringBefore("private fun renderSubCategoryRail(")

        assertTrue(paging.contains("browserSingerRecyclerView"))
        assertTrue(paging.contains("SINGER_GRID_PAGE_SIZE"))
        assertTrue(paging.contains("browserResultsRecyclerView"))
        assertTrue(paging.contains("SONG_GRID_PAGE_SIZE"))
        assertTrue(indicator.contains("singerAdapter.itemCount"))
        assertTrue(indicator.contains("state.catalogTotal"))
    }

    @Test
    fun semanticTabsMapToHomeKtvDataRoutesWithoutHijackingHistory() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val tabBindings = source.substringAfter("// Bind all 11 reference destinations")
            .substringBefore("// 底部分页栏与返回键绑定")

        assertTrue(tabBindings.contains("btnTabHd.setOnClickListener { openCommercialRoute(KioskCommercialRoute.HIGH_DEFINITION) }"))
        assertTrue(tabBindings.contains("btnTabFeatured.setOnClickListener { openCommercialRoute(KioskCommercialRoute.FEATURED) }"))
        assertTrue(tabBindings.contains("btnTabTopics.setOnClickListener { openCommercialRoute(KioskCommercialRoute.THEMES) }"))
        assertTrue(tabBindings.contains("btnTabTikTok.setOnClickListener { openCommercialRoute(KioskCommercialRoute.TIKTOK) }"))
        assertFalse("抖音入口不得再映射到演唱历史", tabBindings.contains("btnTabTikTok") && tabBindings.contains("KioskTab.HISTORY"))
        assertTrue(source.contains("KioskCommercialTagPolicy.findExactTag(controllerState.tags, exactName)"))
        assertTrue(source.contains("tagRootRequestRevision"))
    }

    @Test
    fun singerRegionShortcutsAreMappedToVerifiedRegionAndGenderFilters() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val rail = source.substringAfter("private fun renderSubCategoryRail(")
            .substringBefore("private fun commercialRouteButton(")

        assertTrue(rail.contains("KtvSingerFilterPolicy.filters"))
        assertTrue(rail.contains("KtvSingerRegionPolicy.filters"))
        assertTrue(rail.contains("loadSingerRegion(filter)"))
        assertFalse(source.contains("showUnsupportedSingerRegion"))
    }

    @Test
    fun varietyDestinationsStayVisibleWhenHomeLibraryHasNoMatchingTags() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val render = source.substringAfter("private fun renderAvailableVarietyPrograms(")
            .substringBefore("private fun scrollResultsPage(")

        assertTrue(render.contains("icon.parent as? View)?.visibility = View.VISIBLE"))
        assertTrue(render.contains("btnTabVariety.visibility = View.VISIBLE"))
        assertTrue(render.contains("未匹配入口仍保留"))
    }

    @Test
    fun subcategoryFocusReturnsToItsActiveTopTabAndMovesToResults() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val rail = source.substringAfter("private fun renderSubCategoryRail(")
            .substringBefore("private fun commercialRouteButton(")
        assertTrue(rail.contains("nextFocusUpId = it.id"))
        assertTrue(rail.contains("browserBinding.browserResultsRecyclerView.id"))
        assertTrue(rail.contains("browserBinding.browserSingerRecyclerView.id"))
        assertTrue(rail.contains("preferredFocusLabel"))
        assertTrue(rail.contains("btn.requestFocus()"))
    }

    @Test
    fun threeColumnBrowserIncludesPlaylistsNav() {
        val layoutXml = locate("src/main/res/layout/view_ktv_three_column_browser.xml").readText()
        assertTrue(
            "三栏点歌台必须包含歌单导航入口 browserNavPlaylists",
            layoutXml.contains("android:id=\"@+id/browserNavPlaylists\""),
        )
    }

    @Test
    fun threeColumnBrowserSupportsPlaylistsTab() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val isThreeColumnMethod = source.substringAfter("private fun isThreeColumnTab")
            .substringBefore("showBrowserHint")
        assertTrue(
            "isThreeColumnTab 必须包含 KioskTab.PLAYLISTS",
            isThreeColumnMethod.contains("KioskTab.PLAYLISTS"),
        )

        val renderMethod = source.substringAfter("private fun renderThreeColumnBrowser")
            .substringBefore("private fun isThreeColumnTab")
        assertTrue(
            "renderThreeColumnBrowser 必须包含对 KioskTab.PLAYLISTS 的中栏和结果渲染",
            renderMethod.contains("KioskTab.PLAYLISTS"),
        )
    }

    @Test
    fun kioskOverlayRoutesAllTilesCorrectly() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val tileRouting = source.substringAfter("overlay.kioskDashboardView.onTileSelected = { tile ->")
            .substringBefore("overlay.tabPinyin.setOnClickListener")

        assertTrue(
            "精选专区磁贴必须进入现有歌单页，主题标签入口仍留给分类浏览",
            tileRouting.contains("KtvDashboardTile.THEME ->") &&
                tileRouting.contains("openCommercialRoute(KioskCommercialRoute.THEMES)") &&
                tileRouting.contains("KtvDashboardTile.NEW_SONGS ->") &&
                tileRouting.contains("openCommercialRoute(KioskCommercialRoute.FEATURED)"),
        )
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val candidates = sequence {
            yield(workingDirectory.resolve(relativePath))
            yield(workingDirectory.resolve("app/$relativePath"))
            yield(workingDirectory.resolve("../$relativePath"))
            yield(workingDirectory.resolve("../../$relativePath"))
        }
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
