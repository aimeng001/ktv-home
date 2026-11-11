package com.homektv.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.homektv.tv.R
import kotlin.math.roundToInt

internal data class KtvSettingsLayoutMetrics(
    val scale: Float,
    val rowHeightPx: Int,
    val rowGapPx: Int,
    val horizontalInsetPx: Int,
    val columnGapPx: Int,
    val translationYPx: Int,
)

internal object KtvSettingsLayoutPolicy {
    fun forScreenHeight(heightPx: Int): KtvSettingsLayoutMetrics {
        val scale = (heightPx.coerceAtLeast(1) / 1080f).coerceAtLeast(2f / 3f)
        fun scalePx(referencePx: Int) = (referencePx * scale).roundToInt()
        return KtvSettingsLayoutMetrics(
            scale = scale,
            rowHeightPx = scalePx(100),
            rowGapPx = scalePx(22),
            horizontalInsetPx = scalePx(30),
            columnGapPx = scalePx(20),
            translationYPx = scalePx(-72),
        )
    }
}

/** Builds the original two-column settings grid and an explicit D-pad focus graph. */
class KtvSettingsRowFactory(private val context: Context) {
    private val metrics = KtvSettingsLayoutPolicy.forScreenHeight(
        context.resources.displayMetrics.heightPixels,
    )

    fun populate(
        leftColumn: LinearLayout,
        rightColumn: LinearLayout,
        entries: List<KtvSettingsEntry>,
        onActivate: (KtvSettingsEntry) -> Unit,
    ): Map<String, KtvSettingsRowView> {
        val leftEntries = entries.filter { it.column == KtvSettingsColumn.LEFT }
        val rightEntries = entries.filter { it.column == KtvSettingsColumn.RIGHT }
        require(leftEntries.size == rightEntries.size) { "Settings columns must have matching focus rows" }

        val leftRows = leftEntries.map { KtvSettingsRowView(context, it, onActivate) }
        val rightRows = rightEntries.map { KtvSettingsRowView(context, it, onActivate) }
        leftRows.forEachIndexed { index, row ->
            leftColumn.addView(row, rowLayoutParams(index == leftRows.lastIndex))
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = rightRows[index].id
            row.nextFocusUpId = if (index == 0) row.id else leftRows[index - 1].id
            row.nextFocusDownId = if (index == leftRows.lastIndex) row.id else leftRows[index + 1].id
        }
        rightRows.forEachIndexed { index, row ->
            rightColumn.addView(row, rowLayoutParams(index == rightRows.lastIndex))
            row.nextFocusLeftId = leftRows[index].id
            row.nextFocusRightId = row.id
            row.nextFocusUpId = if (index == 0) row.id else rightRows[index - 1].id
            row.nextFocusDownId = if (index == rightRows.lastIndex) row.id else rightRows[index + 1].id
        }
        return (leftEntries.zip(leftRows) + rightEntries.zip(rightRows))
            .associate { (entry, row) -> entry.key to row }
    }

    private fun rowLayoutParams(last: Boolean) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        metrics.rowHeightPx,
    ).apply {
        if (!last) bottomMargin = metrics.rowGapPx
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

/** A focusable settings card whose checked state is rebound after a persisted toggle. */
class KtvSettingsRowView(
    context: Context,
    initialEntry: KtvSettingsEntry,
    private val onActivate: (KtvSettingsEntry) -> Unit,
) : LinearLayout(context) {
    private val metrics = KtvSettingsLayoutPolicy.forScreenHeight(resources.displayMetrics.heightPixels)
    private val fontScale = resources.configuration.fontScale
    private var entry = initialEntry
    private val icon = ImageView(context)
    private val title = TextView(context)
    private val value = TextView(context)
    private val indicator = FrameLayout(context)
    private val indicatorKnob = View(context)
    private val indicatorArrow = ImageView(context)
    private val labels = LinearLayout(context)
    private val iconHolder = FrameLayout(context)

    init {
        id = View.generateViewId()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        isFocusableInTouchMode = false
        background = ContextCompat.getDrawable(context, R.drawable.bg_ktv_settings_card)
        setPadding(dp(5), 0, dp(5), 0)

        iconHolder.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#aa1fef"))
        }
        iconHolder.layoutParams = LayoutParams(dp(30), dp(30))
        icon.layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
        iconHolder.addView(icon)
        addView(iconHolder)

        labels.apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(6)
            }
        }
        title.setTextColor(Color.WHITE)
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, scaledSpPx(14f))
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        value.setTextColor(Color.rgb(200, 204, 218))
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, scaledSpPx(12f))
        value.maxLines = 1
        value.ellipsize = android.text.TextUtils.TruncateAt.END
        labels.addView(title)
        labels.addView(value)
        addView(labels)

        indicatorArrow.apply {
            setImageResource(R.drawable.ic_ktv_settings_arrow)
            setColorFilter(Color.WHITE)
            layoutParams = LayoutParams(dp(16), dp(16))
        }
        indicator.layoutParams = LayoutParams(dp(40), dp(22))
        addView(indicatorArrow)
        addView(indicator)

        setOnClickListener {
            if (entry.indicator == KtvSettingsIndicator.SWITCH && entry.enabled) {
                onActivate(entry.copy(checked = !entry.checked))
            } else {
                onActivate(entry)
            }
        }
        bind(initialEntry)
    }

    fun bind(updatedEntry: KtvSettingsEntry) {
        entry = updatedEntry
        contentDescription = "${entry.title}，${entry.value}"
        icon.setImageResource(entry.iconRes)
        title.text = entry.title
        value.text = entry.value
        title.alpha = if (entry.enabled) 1f else 0.78f
        value.alpha = if (entry.enabled) 1f else 0.78f
        indicatorArrow.visibility = if (entry.indicator == KtvSettingsIndicator.ARROW) VISIBLE else GONE
        indicator.visibility = if (entry.indicator == KtvSettingsIndicator.ARROW) GONE else VISIBLE
        renderIndicator()
    }

    private fun renderIndicator() {
        indicator.removeAllViews()
        if (entry.indicator == KtvSettingsIndicator.ARROW) return

        val checked = entry.indicator == KtvSettingsIndicator.SWITCH && entry.checked
        val enabled = entry.indicator == KtvSettingsIndicator.SWITCH && entry.enabled
        indicator.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(11).toFloat()
            setColor(
                when {
                    !enabled -> Color.rgb(170, 174, 182)
                    checked -> Color.rgb(53, 190, 134)
                    else -> Color.rgb(170, 174, 182)
                },
            )
        }
        indicatorKnob.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        indicator.addView(indicatorKnob, FrameLayout.LayoutParams(dp(20), dp(20)).apply {
            gravity = if (checked) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = dp(1)
            rightMargin = dp(1)
        })
        indicator.alpha = if (enabled) 1f else 0.82f
    }

    private fun dp(value: Int): Int = (value * 2f * metrics.scale).roundToInt()

    private fun scaledSpPx(value: Float): Float = value * 2f * metrics.scale * fontScale
}
