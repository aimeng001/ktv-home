package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKioskBrowserSingerLayoutTest {

    @Test
    fun singerGridUsesEightColumnsAndSixteenItemsPerPage() {
        val controllerSource = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        // Verify span count is 8
        assertTrue(
            "browserSingerRecyclerView must use GridLayoutManager with 8 columns",
            controllerSource.contains("browserSingerRecyclerView.layoutManager = GridLayoutManager(activity, 8)"),
        )

        // Verify page size is 16 (2 rows x 8 columns)
        assertTrue(
            "SINGER_GRID_PAGE_SIZE must be 16 for 2x8 grid",
            controllerSource.contains("const val SINGER_GRID_PAGE_SIZE = 16"),
        )
    }

    @Test
    fun artistBatchSizeIsAlignedToSixteenForSingerPages() {
        val vmSource = locate("src/main/java/com/homektv/tv/controller/ControllerViewModel.kt").readText()

        assertTrue(
            "ControllerViewModel must request artist batches aligned to 16 (32 = 2 pages)",
            vmSource.contains("const val ARTIST_GRID_PAGE_SIZE = 32"),
        )
        assertTrue(
            "loadArtistsPage must pass size = ARTIST_GRID_PAGE_SIZE",
            vmSource.contains("size = ARTIST_GRID_PAGE_SIZE"),
        )
    }

    @Test
    fun singerCardUsesCircleAvatarAndCompactDimensions() {
        val layoutSource = locate("src/main/res/layout/item_singer_card.xml").readText()

        assertTrue(
            "item_singer_card must use circular avatar background",
            layoutSource.contains("@drawable/bg_avatar_circle"),
        )
        assertTrue(
            "Avatar width must be 72dp for 8-column grid",
            layoutSource.contains("android:layout_width=\"72dp\""),
        )
        assertTrue(
            "Avatar height must be 72dp for 8-column grid",
            layoutSource.contains("android:layout_height=\"72dp\""),
        )
    }

    @Test
    fun singerAndResultsRecyclerViewsDisableItemAnimationsAndSetVisibleBeforeSubmitList() {
        val controllerSource = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(
            "overlay.singerRecyclerView 必须禁用默认 ItemAnimator 避免切页隐身渲染卡顿",
            controllerSource.contains("overlay.singerRecyclerView.itemAnimator = null"),
        )
        assertTrue(
            "browserSingerRecyclerView 必须禁用默认 ItemAnimator 避免歌星网格空白",
            controllerSource.contains("browserBinding.browserSingerRecyclerView.itemAnimator = null"),
        )

        val renderCatalogFn = controllerSource.substringAfter("private fun renderCatalogState(state: ControllerUiState)")
            .substringBefore("private fun renderThreeColumnBrowser")
        assertTrue(
            "提交歌星列表前须先确保 browserSingerRecyclerView 可见并在回调中触发重排",
            renderCatalogFn.indexOf("browserBinding.browserSingerRecyclerView.visibility = View.VISIBLE") in
                0 until renderCatalogFn.indexOf("singerAdapter.submitList(singerPageSlice.items)"),
        )
        assertTrue(
            "歌星网格须按 SINGER_GRID_PAGE_SIZE 硬分页切片提交列表，避免一屏展示多行导致下一页无效",
            renderCatalogFn.contains("KioskSongGridPaginationPolicy.slicePage") &&
                renderCatalogFn.contains("pageSize = SINGER_GRID_PAGE_SIZE"),
        )
        assertTrue(
            "提交歌星列表回调须请求 requestLayout 兜底刷新网格",
            renderCatalogFn.contains("browserBinding.browserSingerRecyclerView.requestLayout()"),
        )

        val renderBrowserFn = controllerSource.substringAfter("private fun renderThreeColumnBrowser(state: ControllerUiState)")
            .substringBefore("private fun syncFocusTargets")
        assertTrue(
            "分类首页加载中应明确展示“正在加载分类…”而非误报“选择一个分类查看歌曲”",
            renderBrowserFn.contains("tab == KioskTab.CATEGORIES && !categoryDetail && state.catalogLoading -> \"正在加载分类…\""),
        )
    }

    private fun locate(relativePath: String): File {
        var current = File(System.getProperty("user.dir").orEmpty())
        repeat(6) {
            val candidate = File(current, "android-tv/app/$relativePath")
            if (candidate.isFile) return candidate
            val candidateDirect = File(current, relativePath)
            if (candidateDirect.isFile) return candidateDirect
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
