package com.homektv.tv.ui.kiosk

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.homektv.tv.R
import com.homektv.tv.databinding.ViewKtvCommercialDashboardBinding

/**
 * 电视点歌首页磁贴视图组件（上排 4 项、下排 3 项；排行榜保留为兼容状态，不展示）。
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
        configureTileFocusLinks()
    }

    private fun configureTileFocusLinks() {
        binding.tilePinyin.tileRoot.apply {
            nextFocusRightId = R.id.tileSinger
            nextFocusDownId = R.id.tileFavorites
        }
        binding.tileSinger.tileRoot.apply {
            nextFocusLeftId = R.id.tilePinyin
            nextFocusRightId = R.id.tileCategory
            nextFocusDownId = R.id.tileFavorites
        }
        binding.tileCategory.tileRoot.apply {
            nextFocusLeftId = R.id.tileSinger
            nextFocusRightId = R.id.tileLanguage
            nextFocusDownId = R.id.tileHistory
        }
        binding.tileLanguage.tileRoot.apply {
            nextFocusLeftId = R.id.tileCategory
            nextFocusDownId = R.id.tileQueue
        }
        binding.tileFavorites.tileRoot.apply {
            nextFocusUpId = R.id.tilePinyin
            nextFocusRightId = R.id.tileHistory
            nextFocusDownId = R.id.btnPlayPause
        }
        binding.tileHistory.tileRoot.apply {
            nextFocusLeftId = R.id.tileFavorites
            nextFocusUpId = R.id.tileCategory
            nextFocusRightId = R.id.tileQueue
            nextFocusDownId = R.id.btnPlayPause
        }
        binding.tileQueue.tileRoot.apply {
            nextFocusLeftId = R.id.tileHistory
            nextFocusUpId = R.id.tileLanguage
            nextFocusDownId = R.id.btnPlayPause
        }
    }

    private fun initTiles() {
        // Search remains the compatibility key, but the visible action is ordinary song search.
        binding.tilePinyin.imgTileIcon.setImageResource(KtvDashboardTile.PINYIN.iconRes)
        binding.tilePinyin.txtTileTitle.text = KtvDashboardTile.PINYIN.title
        binding.tilePinyin.txtTileSubtitle.text = KtvDashboardTile.PINYIN.subtitle
        binding.tilePinyin.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.PINYIN
            onTileClick?.invoke(KtvDashboardTile.PINYIN)
        }

        // 2. 歌手点歌
        binding.tileSinger.imgTileIcon.setImageResource(KtvDashboardTile.SINGER.iconRes)
        binding.tileSinger.txtTileTitle.text = KtvDashboardTile.SINGER.title
        binding.tileSinger.txtTileSubtitle.text = KtvDashboardTile.SINGER.subtitle
        binding.tileSinger.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.SINGER
            onTileClick?.invoke(KtvDashboardTile.SINGER)
        }

        // 3. 分类点歌
        binding.tileCategory.imgTileIcon.setImageResource(KtvDashboardTile.CATEGORY.iconRes)
        binding.tileCategory.txtTileTitle.text = KtvDashboardTile.CATEGORY.title
        binding.tileCategory.txtTileSubtitle.text = KtvDashboardTile.CATEGORY.subtitle
        binding.tileCategory.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.CATEGORY
            onTileClick?.invoke(KtvDashboardTile.CATEGORY)
        }

        // 4. 语种点歌
        binding.tileLanguage.imgTileIcon.setImageResource(KtvDashboardTile.LANGUAGE.iconRes)
        binding.tileLanguage.txtTileTitle.text = KtvDashboardTile.LANGUAGE.title
        binding.tileLanguage.txtTileSubtitle.text = KtvDashboardTile.LANGUAGE.subtitle
        binding.tileLanguage.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.LANGUAGE
            onTileClick?.invoke(KtvDashboardTile.LANGUAGE)
        }

        // 5. 我的收藏
        binding.tileFavorites.imgTileIcon.setImageResource(KtvDashboardTile.FAVORITES.iconRes)
        binding.tileFavorites.txtTileTitle.text = KtvDashboardTile.FAVORITES.title
        binding.tileFavorites.txtTileSubtitle.text = KtvDashboardTile.FAVORITES.subtitle
        binding.tileFavorites.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.FAVORITES
            onTileClick?.invoke(KtvDashboardTile.FAVORITES)
        }

        // 6. 点唱历史
        binding.tileHistory.imgTileIcon.setImageResource(KtvDashboardTile.HISTORY.iconRes)
        binding.tileHistory.txtTileTitle.text = KtvDashboardTile.HISTORY.title
        binding.tileHistory.txtTileSubtitle.text = KtvDashboardTile.HISTORY.subtitle
        binding.tileHistory.tileRoot.setOnClickListener {
            lastFocusedTile = KtvDashboardTile.HISTORY
            onTileClick?.invoke(KtvDashboardTile.HISTORY)
        }

        // 7. 已点歌曲
        binding.tileQueue.imgTileIcon.setImageResource(KtvDashboardTile.ORDERED_QUEUE.iconRes)
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
        val focusTile = KtvDashboardTile.restoreDashboardFocus(lastFocusedTile)
        lastFocusedTile = focusTile
        val targetView = when (focusTile) {
            KtvDashboardTile.PINYIN -> binding.tilePinyin.tileRoot
            KtvDashboardTile.SINGER -> binding.tileSinger.tileRoot
            KtvDashboardTile.CATEGORY -> binding.tileCategory.tileRoot
            KtvDashboardTile.LANGUAGE -> binding.tileLanguage.tileRoot
            KtvDashboardTile.RANKING -> binding.tilePinyin.tileRoot
            KtvDashboardTile.FAVORITES -> binding.tileFavorites.tileRoot
            KtvDashboardTile.HISTORY -> binding.tileHistory.tileRoot
            KtvDashboardTile.ORDERED_QUEUE -> binding.tileQueue.tileRoot
        }
        return targetView.requestFocus()
    }
}
