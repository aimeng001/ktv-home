package com.homektv.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Bounded image decoding for untrusted server/cache bytes. */
internal object BitmapSafety {
    const val MAX_INPUT_BYTES = 10L * 1024L * 1024L
    const val MAX_BITMAP_BYTES = 32L * 1024L * 1024L

    fun decode(
        bytes: ByteArray,
        maxDimension: Int = 1_024,
        maxPixels: Long = 4L * 1024L * 1024L,
        decodeByteArray: (ByteArray, Int, Int, BitmapFactory.Options?) -> Bitmap? = { b, off, len, opts ->
            BitmapFactory.decodeByteArray(b, off, len, opts)
        },
    ): Bitmap? {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_INPUT_BYTES) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxPixels)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val bitmap = decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            if (bitmap.allocationByteCount.toLong() > MAX_BITMAP_BYTES) {
                bitmap.recycle()
                return null
            }
            bitmap
        } catch (t: Throwable) {
            null
        }
    }

    internal fun calculateInSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int,
        maxPixels: Long,
    ): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0 || maxPixels <= 0) return 1
        var sample = 1
        while (width.toLong() / sample > maxDimension ||
            height.toLong() / sample > maxDimension ||
            (width.toLong() / sample) * (height.toLong() / sample) > maxPixels
        ) {
            if (sample >= (1 shl 29)) break
            sample = sample shl 1
        }
        return sample
    }
}
