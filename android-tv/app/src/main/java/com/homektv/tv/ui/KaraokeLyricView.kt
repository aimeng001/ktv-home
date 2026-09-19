package com.homektv.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.homektv.tv.R
import com.homektv.tv.player.LyricLine
import com.homektv.tv.player.LyricSweepCalculator

/**
 * 单行卡拉 OK 歌词视图，按播放进度逐字绘制高亮效果。
 *
 * Single-line karaoke lyric view that reveals highlighted text with playback progress.
 */
class KaraokeLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 42f, resources.displayMetrics)
        setShadowLayer(4f, 2f, 2f, 0xE6000000.toInt())
    }
    private val pendingColor = context.getColor(android.R.color.white)
    private val activeColor = context.getColor(R.color.karaoke_lyric_blue)
    private var line: LyricLine? = null
    private var lineEndMs = 0L
    private var sampledPositionMs = 0L
    private var sampledAtMs = 0L
    private var playing = false

    /**
     * 设置当前歌词行及其结束时间。
     *
     * Sets the current lyric line and its end timestamp.
     */
    fun setLine(value: LyricLine?, endMs: Long) {
        if (line == value && lineEndMs == endMs) return
        line = value
        lineEndMs = maxOf(endMs, (value?.startMs ?: 0L) + 1L)
        contentDescription = value?.text.orEmpty()
        adjustTextSize(width, height)
        invalidate()
    }

    fun updatePlayback(positionMs: Long, isPlaying: Boolean) {
        sampledPositionMs = positionMs
        sampledAtMs = SystemClock.elapsedRealtime()
        playing = isPlaying
        invalidate()
    }

    fun stopAnimation() {
        playing = false
        line = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        adjustTextSize(w, h)
    }

    private fun adjustTextSize(w: Int, h: Int) {
        if (h <= 0) return
        val baseSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 42f, resources.displayMetrics)
        paint.textSize = baseSize
        val fm = paint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val maxAllowedHeight = h * 0.82f
        var scale = if (textHeight > maxAllowedHeight && maxAllowedHeight > 0f) {
            maxAllowedHeight / textHeight
        } else 1f

        val currentText = line?.text.orEmpty()
        if (w > 0 && currentText.isNotEmpty()) {
            paint.textSize = baseSize * scale
            val textWidth = paint.measureText(currentText)
            val maxAllowedWidth = w * 0.94f
            if (textWidth > maxAllowedWidth && maxAllowedWidth > 0f) {
                scale *= (maxAllowedWidth / textWidth)
            }
        }
        paint.textSize = baseSize * scale.coerceIn(0.35f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = line ?: return
        val text = current.text
        if (text.isEmpty()) return
        val totalWidth = paint.measureText(text)
        val left = (width - totalWidth) / 2f
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        val position = sampledPositionMs + if (playing) SystemClock.elapsedRealtime() - sampledAtMs else 0L

        paint.color = pendingColor
        canvas.drawText(text, left, baseline, paint)

        val anchors = buildAnchors(current, text, totalWidth)
        val revealed = LyricSweepCalculator.offsetAt(position, anchors, totalWidth)
        if (revealed > 0f) {
            canvas.save()
            canvas.clipRect(left, 0f, left + revealed, height.toFloat())
            paint.color = activeColor
            canvas.drawText(text, left, baseline, paint)
            canvas.restore()
        }
        if (playing && position < lineEndMs) postInvalidateOnAnimation()
    }

    private fun buildAnchors(current: LyricLine, text: String, totalWidth: Float): List<LyricSweepCalculator.Anchor> {
        val anchors = mutableListOf(LyricSweepCalculator.Anchor(current.startMs, 0f))
        var prefixLength = 0
        current.words.forEach { word ->
            anchors += LyricSweepCalculator.Anchor(
                word.startMs,
                paint.measureText(text.substring(0, prefixLength.coerceAtMost(text.length))),
            )
            prefixLength += word.text.length
        }
        anchors += LyricSweepCalculator.Anchor(lineEndMs, totalWidth)
        return anchors
    }
}
