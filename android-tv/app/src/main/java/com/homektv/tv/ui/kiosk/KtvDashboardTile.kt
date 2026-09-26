package com.homektv.tv.ui.kiosk

import androidx.annotation.DrawableRes
import com.homektv.tv.R

enum class KtvDashboardTile(
    val title: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    val gradientStartColor: Long,
    val gradientEndColor: Long,
) {
    PINYIN("搜索点歌", "按歌名、中文或拼音首字母搜索", R.drawable.ic_pinyin, 0xFF6B21A8, 0xFF9333EA),
    SINGER("歌星点歌", "按歌手筛选歌曲", R.drawable.ic_singer, 0xFFC2410C, 0xFFEA580C),
    CATEGORY("分类点歌", "按曲库分类浏览", R.drawable.ic_category, 0xFF0D9488, 0xFF14B8A6),
    LANGUAGE("语种点歌", "按曲库语种浏览", R.drawable.ic_language, 0xFF1D4ED8, 0xFF3B82F6),
    RANKING("排行榜单", "热歌榜/新歌飙升榜", R.drawable.ic_ranking, 0xFFBE123C, 0xFFE11D48),
    FAVORITES("我的收藏", "已收藏的歌曲", R.drawable.ic_favorites, 0xFFD97706, 0xFFF59E0B),
    HISTORY("曾经点唱", "最近点过的歌曲", R.drawable.ic_history, 0xFF0891B2, 0xFF06B6D4),
    ORDERED_QUEUE("已点歌曲", "正在播放与排队歌曲", R.drawable.ic_queue, 0xFF047857, 0xFF10B981);

    companion object {
        /** Visible TV-home actions. RANKING stays in the enum for legacy compatibility only. */
        val dashboardEntries = listOf(PINYIN, SINGER, CATEGORY, LANGUAGE, FAVORITES, HISTORY, ORDERED_QUEUE)

        fun restoreDashboardFocus(tile: KtvDashboardTile): KtvDashboardTile =
            tile.takeIf { it in dashboardEntries } ?: PINYIN
    }
}
