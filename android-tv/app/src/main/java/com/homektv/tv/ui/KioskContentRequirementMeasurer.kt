package com.homektv.tv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.R
import com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
import com.homektv.tv.ui.kiosk.KtvCommercialDashboardView
import kotlin.math.ceil

/** Measures only the active kiosk page's first complete, usable content area. */
internal class KioskContentRequirementMeasurer(context: Context) {
    private val resources = context.resources
    private val layoutInflater = LayoutInflater.from(context)
    private val fallbackItemHeightsPx = mutableMapOf<Pair<Int, Int>, Int>()

    fun pinyin(overlay: ViewKtvKioskOverlayBinding): KioskContentHeightRequirement? {
        val keyboard = overlay.kioskKeyboard
        val resultsPanel = overlay.searchResultsPanel
        if (keyboard.width <= 0 || resultsPanel.width <= 0) return null

        val keyboardHeightPx = keyboard.measureNaturalContentHeightPx(keyboard.width)
        if (keyboardHeightPx <= 0) return null

        val headerHeightPx = measuredOuterHeightPx(overlay.txtSearchCount)
        val searchRowHeightPx = firstVisibleRecyclerRowHeightPx(
            recycler = overlay.searchRecyclerView,
            fallbackItemLayout = R.layout.item_kiosk_song_row,
        )
        val minimumViewportPx = dpToPxCeil(MINIMUM_PINYIN_VIEWPORT_DP)
        val resultsHeightPx = headerHeightPx + dpToPxCeil(8) + maxOf(searchRowHeightPx, minimumViewportPx)
        return KioskContentRequirementPolicy.pinyin(
            keyboardNaturalHeightDp = pxToDpCeil(keyboardHeightPx),
            resultsMinimumHeightDp = pxToDpCeil(resultsHeightPx),
            minimumScrollableViewportDp = MINIMUM_PINYIN_VIEWPORT_DP,
        )
    }

    fun dashboard(view: KtvCommercialDashboardView): KioskContentHeightRequirement? {
        if (view.width <= 0 || view.height <= 0) return null

        val dashboard = view.binding
        val rows = listOf(
            listOf(
                dashboard.tilePinyin.tileRoot,
                dashboard.tileSinger.tileRoot,
                dashboard.tileCategory.tileRoot,
                dashboard.tileLanguage.tileRoot,
            ),
            listOf(
                dashboard.tileFavorites.tileRoot,
                dashboard.tileHistory.tileRoot,
                dashboard.tileQueue.tileRoot,
            ),
        )
        val minimumTileHeightPx = resources.getDimensionPixelSize(R.dimen.kiosk_dashboard_tile_min_height)
        val rowHeightsPx = rows.map { tiles ->
            tiles.maxOf { tile -> minimumTileHeightPx + verticalMarginsPx(tile) }
        }
        val root = dashboard.dashboardRoot
        val rowViews = listOf(dashboard.dashboardTopRow, dashboard.dashboardBottomRow)
        val spacingPx = root.paddingTop + root.paddingBottom + rowViews.sumOf(::verticalMarginsPx)
        return KioskContentRequirementPolicy.dashboard(
            topRowHeightDp = pxToDpCeil(rowHeightsPx[0]),
            bottomRowHeightDp = pxToDpCeil(rowHeightsPx[1]),
            interRowAndOuterSpacingDp = pxToDpCeil(spacingPx),
        )
    }

    fun listPage(
        chrome: List<View>,
        recycler: RecyclerView,
        fallbackItemLayout: Int?,
        statusPanel: View? = null,
    ): KioskContentHeightRequirement? {
        val visibleStatusPanel = statusPanel?.takeIf { it.visibility == View.VISIBLE }
        val contentWidth = visibleStatusPanel?.width ?: recycler.width
        if (contentWidth <= 0) return null
        if (chrome.any { it.visibility == View.VISIBLE && (it.width <= 0 || it.measuredHeight <= 0) }) return null
        if (visibleStatusPanel != null && visibleStatusPanel.measuredHeight <= 0) return null

        val chromeHeightPx = chrome
            .filter { it.visibility == View.VISIBLE }
            .sumOf(::measuredOuterHeightPx)
        val itemAreaHeightPx = if (visibleStatusPanel != null) {
            measuredOuterHeightPx(visibleStatusPanel)
        } else {
            maxOf(
                firstVisibleRecyclerRowHeightPx(recycler, fallbackItemLayout),
                dpToPxCeil(MINIMUM_LIST_VIEWPORT_DP),
            )
        }
        return KioskContentRequirementPolicy.listPage(
            chromeHeightDp = pxToDpCeil(chromeHeightPx),
            firstItemRowHeightDp = pxToDpCeil(itemAreaHeightPx),
            minimumScrollableViewportDp = MINIMUM_LIST_VIEWPORT_DP,
        )
    }

    private fun firstVisibleRecyclerRowHeightPx(recycler: RecyclerView, fallbackItemLayout: Int?): Int {
        val layoutManager = recycler.layoutManager
        val children = (0 until recycler.childCount)
            .map(recycler::getChildAt)
            .filter { it.visibility == View.VISIBLE }
        val firstPosition = children
            .map { recycler.getChildAdapterPosition(it) }
            .filter { it != RecyclerView.NO_POSITION }
            .minOrNull()
        if (layoutManager != null && firstPosition != null) {
            val gridLayout = layoutManager as? GridLayoutManager
            val firstRow = gridLayout?.spanSizeLookup?.getSpanGroupIndex(firstPosition, gridLayout.spanCount)
            return children
                .filter { child ->
                    val position = recycler.getChildAdapterPosition(child)
                    if (position == RecyclerView.NO_POSITION) return@filter false
                    if (gridLayout != null && firstRow != null) {
                        gridLayout.spanSizeLookup.getSpanGroupIndex(position, gridLayout.spanCount) == firstRow
                    } else {
                        position == firstPosition
                    }
                }
                .maxOfOrNull(::measuredOuterHeightPx)
                ?: 0
        }
        return fallbackItemLayout?.let { measureFallbackItemHeightPx(recycler, it) } ?: 0
    }

    private fun measureFallbackItemHeightPx(recycler: RecyclerView, layoutResource: Int): Int {
        val widthPx = (recycler.width - recycler.paddingLeft - recycler.paddingRight).coerceAtLeast(1)
        val spanCount = (recycler.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        val itemWidthPx = (widthPx / spanCount).coerceAtLeast(1)
        val cacheKey = layoutResource to itemWidthPx
        return fallbackItemHeightsPx.getOrPut(cacheKey) {
            val itemView = layoutInflater.inflate(layoutResource, recycler, false)
            when (layoutResource) {
                R.layout.item_singer_card -> {
                    itemView.findViewById<TextView>(R.id.txtSingerName)?.text = "歌手名称"
                    itemView.findViewById<TextView>(R.id.txtSingerSongCount)?.text = "0 首"
                }
                R.layout.item_kiosk_named_count -> {
                    itemView.findViewById<TextView>(R.id.txtNamedCountName)?.text = "分类名称"
                    itemView.findViewById<TextView>(R.id.txtNamedCountSongs)?.text = "0 首歌曲"
                }
            }
            val params = itemView.layoutParams as? ViewGroup.MarginLayoutParams
            val horizontalMargins = (params?.leftMargin ?: 0) + (params?.rightMargin ?: 0)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                (itemWidthPx - horizontalMargins).coerceAtLeast(1),
                View.MeasureSpec.EXACTLY,
            )
            val unspecifiedHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            itemView.measure(widthSpec, unspecifiedHeight)
            itemView.measuredHeight + verticalMarginsPx(itemView)
        }
    }

    private fun measuredOuterHeightPx(view: View): Int = if (view.visibility == View.VISIBLE) {
        view.measuredHeight + verticalMarginsPx(view)
    } else {
        0
    }

    private fun verticalMarginsPx(view: View): Int {
        val margins = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return 0
        return margins.topMargin + margins.bottomMargin
    }

    private fun pxToDpCeil(pixels: Int): Int =
        ceil(pixels.coerceAtLeast(0) / resources.displayMetrics.density).toInt()

    private fun dpToPxCeil(dp: Int): Int =
        ceil(dp.coerceAtLeast(0) * resources.displayMetrics.density).toInt()

    private companion object {
        const val MINIMUM_PINYIN_VIEWPORT_DP = 140
        const val MINIMUM_LIST_VIEWPORT_DP = 128
    }
}
