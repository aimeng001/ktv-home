package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvDashboardResponsivePolicyTest {

    @Test
    fun testLegacyFixed340PipCausesMobileTitleTruncation() {
        // 模拟用户截图场景：手机横屏 700dp，固定 340dp PIP，旧 margin 8dp，旧 padding 20dp，旧字号 22sp
        val legacyTileUsableWidth = KtvDashboardResponsivePolicy.calculateTileUsableWidthDp(
            screenWidthDp = 700,
            pipWidthDp = 340,
            columnCount = 4,
            tileMarginDp = 8,
            tilePaddingHorizontalDp = 20,
        )
        // 4字标题（如“拼音点歌”）在 22sp 下必然截断
        val fits = KtvDashboardResponsivePolicy.canFitTitleWithoutTruncation(
            title = "拼音点歌",
            usableWidthDp = legacyTileUsableWidth,
            textSizeSp = 22f,
        )
        assertFalse("旧布局下 14dp 可用宽必然导致 4 字标题截断", fits)
    }

    @Test
    fun testResponsivePolicyEnsuresMobileTitleFitsWithoutTruncation() {
        val phoneScreenWidthDp = 700
        val responsivePipWidth = KtvDashboardResponsivePolicy.calculatePipWidthDp(phoneScreenWidthDp)
        val responsiveTileUsableWidth = KtvDashboardResponsivePolicy.calculateTileUsableWidthDp(
            screenWidthDp = phoneScreenWidthDp,
            pipWidthDp = responsivePipWidth,
            columnCount = 4,
            tileMarginDp = KtvDashboardResponsivePolicy.DEFAULT_TILE_MARGIN_DP,
            tilePaddingHorizontalDp = KtvDashboardResponsivePolicy.DEFAULT_TILE_PADDING_HORIZONTAL_DP,
        )

        // 验证 8 个一级大厅磁贴标题在手机上均完整显示，无一截断
        val tiles = KtvDashboardTile.values()
        for (tile in tiles) {
            val fits = KtvDashboardResponsivePolicy.canFitTitleWithoutTruncation(
                title = tile.title,
                usableWidthDp = responsiveTileUsableWidth,
                textSizeSp = 16f,
            )
            assertTrue("自适应策略下【${tile.title}】必须完整容纳不被截断", fits)
        }
    }

    @Test
    fun testTvScreenRetainsGenerousPipWidth() {
        // 1080p TV（标准 960dp~1920dp 逻辑宽）
        val pipWidth1k = KtvDashboardResponsivePolicy.calculatePipWidthDp(960)
        assertTrue("1K TV 下 PIP 宽度应在 280dp~340dp 之间保持极佳观感", pipWidth1k in 280..340)

        val pipWidth4k = KtvDashboardResponsivePolicy.calculatePipWidthDp(1920)
        assertTrue("4K TV 下 PIP 宽度不应超过上限 340dp", pipWidth4k <= 340)
    }
}
