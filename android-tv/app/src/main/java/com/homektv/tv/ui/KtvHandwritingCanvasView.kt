package com.homektv.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/** Touch, mouse, and D-pad ink surface. Strokes are local UI state and are never OCR'd. */
class KtvHandwritingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    val strokeBuffer = KtvHandwritingStrokeBuffer()

    var isRemoteDrawing: Boolean = false
        private set

    private var remoteStrokeStarted = false
    private var cursorX = 0.5f
    private var cursorY = 0.5f
    private val density = resources.displayMetrics.density
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE4ECFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8AB4FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "手写画板，按确定开始或结束遥控器绘制"
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            cornerRadius = 5f * density
            setStroke(max(1, (density + 0.5f).toInt()), 0x45B8B8B8)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 4f * density
        val inkBounds = RectF(inset, inset, width - inset, height - inset)
        if (inkBounds.width() <= 0f || inkBounds.height() <= 0f) return

        strokeBuffer.snapshot().forEach { stroke ->
            if (stroke.isEmpty()) return@forEach
            val path = Path().apply {
                val first = stroke.first()
                moveTo(toX(first.x, inkBounds), toY(first.y, inkBounds))
                stroke.drop(1).forEach { point -> lineTo(toX(point.x, inkBounds), toY(point.y, inkBounds)) }
            }
            canvas.drawPath(path, inkPaint)
        }

        if (hasFocus() || isRemoteDrawing) {
            val x = toX(cursorX, inkBounds)
            val y = toY(cursorY, inkBounds)
            canvas.drawCircle(x, y, 6f * density, cursorPaint)
            canvas.drawLine(x - 10f * density, y, x + 10f * density, y, cursorPaint)
            canvas.drawLine(x, y - 10f * density, x, y + 10f * density, cursorPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val x = normalizedX(event.x)
        val y = normalizedY(event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isRemoteDrawing) finishRemoteDrawing()
                requestFocus()
                cursorX = x
                cursorY = y
                strokeBuffer.startStroke(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.historySize) {
                    strokeBuffer.appendPoint(normalizedX(event.getHistoricalX(index)), normalizedY(event.getHistoricalY(index)))
                }
                strokeBuffer.appendPoint(x, y)
                cursorX = x
                cursorY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                strokeBuffer.appendPoint(x, y)
                strokeBuffer.finishStroke()
                cursorX = x
                cursorY = y
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                strokeBuffer.finishStroke()
                remoteStrokeStarted = false
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isRemoteDrawing) finishRemoteDrawing() else startRemoteDrawing()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isRemoteDrawing) return false
                moveRemotePen(keyCode)
                return true
            }
            KeyEvent.KEYCODE_BACK -> if (finishRemoteDrawing()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Returns true only when Back consumed an active remote drawing session. */
    fun finishRemoteDrawing(): Boolean {
        if (!isRemoteDrawing) return false
        strokeBuffer.finishStroke()
        isRemoteDrawing = false
        remoteStrokeStarted = false
        updateDrawingDescription()
        invalidate()
        return true
    }

    fun clearInk() {
        strokeBuffer.clear()
        isRemoteDrawing = false
        remoteStrokeStarted = false
        cursorX = 0.5f
        cursorY = 0.5f
        updateDrawingDescription()
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        refreshBorder()
        invalidate()
    }

    private fun startRemoteDrawing() {
        isRemoteDrawing = true
        remoteStrokeStarted = false
        updateDrawingDescription()
        announceForAccessibility("开始手写")
        invalidate()
    }

    private fun moveRemotePen(keyCode: Int) {
        val step = 0.035f
        val nextX = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> (cursorX - step).coerceAtLeast(0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> (cursorX + step).coerceAtMost(1f)
            else -> cursorX
        }
        val nextY = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> (cursorY - step).coerceAtLeast(0f)
            KeyEvent.KEYCODE_DPAD_DOWN -> (cursorY + step).coerceAtMost(1f)
            else -> cursorY
        }
        if (!remoteStrokeStarted) {
            strokeBuffer.startStroke(cursorX, cursorY)
            remoteStrokeStarted = true
        }
        cursorX = nextX
        cursorY = nextY
        strokeBuffer.appendPoint(cursorX, cursorY)
        invalidate()
    }

    private fun updateDrawingDescription() {
        contentDescription = if (isRemoteDrawing) {
            "正在手写，方向键绘制，按确定结束，按返回结束绘制"
        } else {
            "手写画板，按确定开始或结束遥控器绘制"
        }
        refreshBorder()
    }

    private fun refreshBorder() {
        val strokeColor = if (hasFocus() || isRemoteDrawing) 0xFF315BF5.toInt() else 0x45B8B8B8
        (background as? GradientDrawable)?.setStroke(
            max(1, (density + 0.5f).toInt()),
            strokeColor,
        )
    }

    private fun normalizedX(x: Float): Float = if (width <= 0) 0.5f else (x / width).coerceIn(0f, 1f)

    private fun normalizedY(y: Float): Float = if (height <= 0) 0.5f else (y / height).coerceIn(0f, 1f)

    private fun toX(x: Float, bounds: RectF): Float = bounds.left + x * bounds.width()

    private fun toY(y: Float, bounds: RectF): Float = bounds.top + y * bounds.height()
}
