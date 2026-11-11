package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KtvHandwritingStrokeBufferTest {
    @Test
    fun storesNormalizedStrokePointsAndSeparatesStrokes() {
        val buffer = KtvHandwritingStrokeBuffer()
        buffer.startStroke(0.1f, 0.2f)
        buffer.appendPoint(1.2f, -0.1f)
        buffer.finishStroke()
        buffer.startStroke(0.5f, 0.6f)
        buffer.finishStroke()

        assertEquals(
            listOf(
                listOf(KtvInkPoint(0.1f, 0.2f), KtvInkPoint(1f, 0f)),
                listOf(KtvInkPoint(0.5f, 0.6f)),
            ),
            buffer.snapshot(),
        )
    }

    @Test
    fun clearDropsInkAndIncompleteStroke() {
        val buffer = KtvHandwritingStrokeBuffer()
        buffer.startStroke(0.5f, 0.5f)
        buffer.appendPoint(0.6f, 0.6f)

        buffer.clear()

        assertEquals(emptyList<List<KtvInkPoint>>(), buffer.snapshot())
    }
}
