package com.homektv.tv.ui.kiosk

enum class KtvDashboardTile(
    val title: String,
    val subtitle: String,
    val iconResName: String,
    val gradientStartColor: Long,
    val gradientEndColor: Long,
) {
    PINYIN("拼音点歌", "首字母九宫格快速搜歌", "ic_pinyin", 0xFF6B21A8, 0xFF9333EA),
    SINGER("歌星点歌", "按男歌手/女歌手/组合筛选", "ic_singer", 0xFFC2410C, 0xFFEA580C),
    CATEGORY("分类点歌", "流行/经典/儿歌/戏曲", "ic_category", 0xFF0D9488, 0xFF14B8A6),
    LANGUAGE("语种点歌", "国语/粤语/闽南/英语", "ic_language", 0xFF1D4ED8, 0xFF3B82F6),
    RANKING("排行榜单", "热歌榜/新歌飙升榜", "ic_ranking", 0xFFBE123C, 0xFFE11D48),
    FAVORITES("我的收藏", "私房拿手常唱好歌", "ic_favorites", 0xFFD97706, 0xFFF59E0B),
    HISTORY("曾经点唱", "最近唱过与历史记录", "ic_history", 0xFF0891B2, 0xFF06B6D4),
    ORDERED_QUEUE("已点歌曲", "排队播控与切歌插播", "ic_queue", 0xFF047857, 0xFF10B981),
}
