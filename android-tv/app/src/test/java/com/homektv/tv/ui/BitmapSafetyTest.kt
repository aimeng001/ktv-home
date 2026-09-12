package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitmapSafetyTest {
    @Test
    fun `sample size limits dimensions and decoded pixels`() {
        assertEquals(4, BitmapSafety.calculateInSampleSize(8_000, 4_000, 2_048, 4_000_000))
    }

    @Test
    fun `invalid bounds use safe default`() {
        assertEquals(1, BitmapSafety.calculateInSampleSize(0, 100, 512, 1_000_000))
    }

    @Test
    fun `decode returns null for empty or oversized inputs`() {
        assertNull(BitmapSafety.decode(ByteArray(0)))
        assertNull(BitmapSafety.decode(ByteArray((10L * 1024L * 1024L + 1).toInt())))
    }

    @Test
    fun `decode catches OutOfMemoryError and returns null`() {
        val result = BitmapSafety.decode(
            bytes = ByteArray(16),
            decodeByteArray = { _, _, _, opts ->
                if (opts?.inJustDecodeBounds == true) {
                    opts.outWidth = 200
                    opts.outHeight = 200
                    null
                } else {
                    throw OutOfMemoryError("Simulated OOM during bitmap decoding")
                }
            }
        )
        assertNull(result)
    }
}

