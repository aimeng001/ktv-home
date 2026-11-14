package com.homektv.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * 专用于已点队列列表项的 4 柱微型音频跳动波形。
 * 对标 IKTV 50.0.4 正在播放项的 SpectrumView。
 */
class KtvMiniSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700") // 亮金黄色
    }
    private val density = resources.displayMetrics.density

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncSpectrumAnimationVisibility()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncSpectrumAnimationVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncSpectrumAnimationVisibility()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = 4
        val gap = 2f * density
        val totalWidth = width.toFloat()
        val totalHeight = height.toFloat()
        val barWidth = ((totalWidth - gap * (count - 1)) / count).coerceAtLeast(2f * density)
        val now = SystemClock.uptimeMillis() / 1000f

        for (i in 0 until count) {
            // 各柱独立的起伏频率与相位
            val phase = when (i) {
                0 -> 2.3f
                1 -> 4.1f
                2 -> 3.2f
                else -> 1.8f
            }
            val speed = when (i) {
                0 -> 5.2f
                1 -> 7.8f
                2 -> 6.1f
                else -> 4.5f
            }
            val wave = 0.25f + 0.75f * abs(sin(now * speed + phase))
            val barHeight = (totalHeight * wave).coerceIn(4f * density, totalHeight)
            val left = i * (barWidth + gap)
            val top = totalHeight - barHeight
            val radius = barWidth / 2f
            canvas.drawRoundRect(left, top, left + barWidth, totalHeight, radius, radius, paint)
        }

        // 仅在可见且附着到窗口时调度下一帧动画，杜绝祖先视图隐藏时的后台消耗
        if (SpectrumAnimationPolicy.shouldScheduleNextFrame(isShown, isAttachedToWindow)) {
            postInvalidateOnAnimation()
        }
    }
}
