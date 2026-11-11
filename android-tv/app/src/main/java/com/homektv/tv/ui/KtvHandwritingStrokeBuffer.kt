package com.homektv.tv.ui

/** A point stored relative to the drawing surface, so ink survives viewport resizing. */
data class KtvInkPoint(val x: Float, val y: Float)

/** Keeps only the current, in-memory handwriting strokes; it performs no character recognition. */
class KtvHandwritingStrokeBuffer {
    private val strokes = mutableListOf<MutableList<KtvInkPoint>>()
    private var activeStroke: MutableList<KtvInkPoint>? = null

    fun startStroke(x: Float, y: Float) {
        activeStroke = mutableListOf(normalize(x, y)).also(strokes::add)
    }

    fun appendPoint(x: Float, y: Float) {
        val stroke = activeStroke ?: return startStroke(x, y)
        stroke += normalize(x, y)
    }

    fun finishStroke() {
        activeStroke = null
    }

    fun clear() {
        activeStroke = null
        strokes.clear()
    }

    fun snapshot(): List<List<KtvInkPoint>> = strokes.map(List<KtvInkPoint>::toList)

    private fun normalize(x: Float, y: Float) = KtvInkPoint(
        x = x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
        y = y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
    )
}
