package com.homektv.tv.ui.kiosk

import androidx.annotation.DrawableRes
import com.homektv.tv.R

/** The ten Home KTV routes shown in the reference hall, in stable focus-restoration order. */
enum class KtvDashboardTile(
    val title: String,
    val subtitle: String,
    @DrawableRes val artworkRes: Int,
) {
    HOT_RANKING("热歌新歌", "浏览热门与新歌", R.drawable.ktv_ref_v50_tile_hot_new),
    NEW_SONGS("精选专区", "浏览 Home KTV 可用歌单", R.drawable.ktv_ref_v50_tile_featured),
    SINGER("歌手点歌", "按歌手浏览曲库", R.drawable.ktv_ref_v50_tile_singers_01),
    SONG_NAME("歌名点歌", "拼音与首字母搜索", R.drawable.ktv_ref_v50_tile_songs_01),
    LANGUAGE("语种点歌", "按曲库语种筛选", R.drawable.ktv_ref_v50_tile_language),
    THEME("主题点歌", "按曲库标签筛选", R.drawable.ktv_ref_v50_tile_topic),
    OPERA("综艺点歌", "按曲库节目标签筛选", R.drawable.ktv_ref_v50_tile_variety),
    TIKTOK("抖音神曲", "按曲库标签筛选", R.drawable.ktv_ref_v50_tile_tiktok),
    FAVORITES("我的收藏", "查看 Home KTV 收藏", R.drawable.ktv_ref_v50_tile_favorite),
    LOCAL("本地媒体", "浏览 Home KTV 已索引曲目", R.drawable.ktv_ref_v50_tile_local);

    companion object {
        val dashboardEntries: List<KtvDashboardTile> = entries.toList()

        fun restoreDashboardFocus(tile: KtvDashboardTile?): KtvDashboardTile =
            tile?.takeIf { it in dashboardEntries } ?: HOT_RANKING
    }
}
