package com.homektv.tv.ui.kiosk

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.homektv.tv.databinding.ViewKtvCommercialDashboardBinding

/**
 * 商业点歌机首页大磁贴控制台视图组件（4x2 矩阵，8个核心功能磁贴）。
 */
class KtvCommercialDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    val binding = ViewKtvCommercialDashboardBinding.inflate(LayoutInflater.from(context), this, true)

    var onTileClick: ((KtvDashboardTile) -> Unit)? = null
    var lastFocusedTile: KtvDashboardTile = KtvDashboardTile.PINYIN

    init {
        initTiles()
    }

    private fun initTiles() {
        // 1. 拼音搜歌
        binding.tilePinyin.txtTileTitle.text = KtvDashboardTile.PINYIN.title
        binding.tilePinyin.txtTileSubtitle.text = KtvDashboardTile.PINYIN.subtitle
        binding.tilePinyin.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.PINYIN
            onTileClick?.invoke(KtvDashboardTile.PINYIN)
        }

        // 2. 歌星点歌
        binding.tileSinger.txtTileTitle.text = KtvDashboardTile.SINGER.title
        binding.tileSinger.txtTileSubtitle.text = KtvDashboardTile.SINGER.subtitle
        binding.tileSinger.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.SINGER
            onTileClick?.invoke(KtvDashboardTile.SINGER)
        }

        // 3. 分类点歌
        binding.tileCategory.txtTileTitle.text = KtvDashboardTile.CATEGORY.title
        binding.tileCategory.txtTileSubtitle.text = KtvDashboardTile.CATEGORY.subtitle
        binding.tileCategory.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.CATEGORY
            onTileClick?.invoke(KtvDashboardTile.CATEGORY)
        }

        // 4. 语种点歌
        binding.tileLanguage.txtTileTitle.text = KtvDashboardTile.LANGUAGE.title
        binding.tileLanguage.txtTileSubtitle.text = KtvDashboardTile.LANGUAGE.subtitle
        binding.tileLanguage.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.LANGUAGE
            onTileClick?.invoke(KtvDashboardTile.LANGUAGE)
        }

        // 5. 排行榜单
        binding.tileRanking.txtTileTitle.text = KtvDashboardTile.RANKING.title
        binding.tileRanking.txtTileSubtitle.text = KtvDashboardTile.RANKING.subtitle
        binding.tileRanking.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.RANKING
            onTileClick?.invoke(KtvDashboardTile.RANKING)
        }

        // 6. 我的收藏
        binding.tileFavorites.txtTileTitle.text = KtvDashboardTile.FAVORITES.title
        binding.tileFavorites.txtTileSubtitle.text = KtvDashboardTile.FAVORITES.subtitle
        binding.tileFavorites.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.FAVORITES
            onTileClick?.invoke(KtvDashboardTile.FAVORITES)
        }

        // 7. 曾经点唱
        binding.tileHistory.txtTileTitle.text = KtvDashboardTile.HISTORY.title
        binding.tileHistory.txtTileSubtitle.text = KtvDashboardTile.HISTORY.subtitle
        binding.tileHistory.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.HISTORY
            onTileClick?.invoke(KtvDashboardTile.HISTORY)
        }

        // 8. 已点歌曲
        binding.tileQueue.txtTileTitle.text = KtvDashboardTile.ORDERED_QUEUE.title
        binding.tileQueue.txtTileSubtitle.text = KtvDashboardTile.ORDERED_QUEUE.subtitle
        binding.tileQueue.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.ORDERED_QUEUE
            onTileClick?.invoke(KtvDashboardTile.ORDERED_QUEUE)
        }
    }

    fun updateQueueCount(count: Int) {
        binding.tileQueue.txtTileSubtitle.text = if (count > 0) "当前排队 $count 首歌曲" else "暂无排队歌曲"
    }

    fun requestDashboardFocus(): Boolean {
        val targetView = when (lastFocusedTile) {
            KtvDashboardTile.PINYIN -> binding.tilePinyin.tileRoot
            KtvDashboardTile.SINGER -> binding.tileSinger.tileRoot
            KtvDashboardTile.CATEGORY -> binding.tileCategory.tileRoot
            KtvDashboardTile.LANGUAGE -> binding.tileLanguage.tileRoot
            KtvDashboardTile.RANKING -> binding.tileRanking.tileRoot
            KtvDashboardTile.FAVORITES -> binding.tileFavorites.tileRoot
            KtvDashboardTile.HISTORY -> binding.tileHistory.tileRoot
            KtvDashboardTile.ORDERED_QUEUE -> binding.tileQueue.tileRoot
        }
        return targetView.requestFocus()
    }
}
