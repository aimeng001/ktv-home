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
 * 音频频谱装饰视图，根据麦克风采样绘制动态柱状效果。
 *
 * Decorative audio-spectrum view that draws animated bars from microphone samples.
 */
class AudioSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 100, 124)
        alpha = 178
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
        val count = 96
        val gap = 3f * density
        val barWidth = ((width - gap * (count - 1)) / count).coerceAtLeast(2f * density)
        val now = SystemClock.uptimeMillis() / 1000f
        repeat(count) { index ->
            val envelope = 0.22f + 0.78f * abs(sin(index * 0.37f + now * 2.1f))
            val pulse = 0.42f + 0.58f * abs(sin(index * 0.91f - now * 4.3f))
            val barHeight = height * (0.06f + 0.72f * envelope * pulse)
            val left = index * (barWidth + gap)
            val top = (height - barHeight) / 2f
            canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, barWidth / 2f, barWidth / 2f, paint)
        }
        if (SpectrumAnimationPolicy.shouldScheduleNextFrame(isShown, isAttachedToWindow)) {
            postInvalidateOnAnimation()
        }
    }
}
