package com.homektv.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal enum class ArtworkProfile(
    val maxDimension: Int,
    val maxPixels: Long,
) {
    COVER(maxDimension = 1_024, maxPixels = 4L * 1024L * 1024L),
    LOGO(maxDimension = 1_024, maxPixels = 4L * 1024L * 1024L),
    QR(maxDimension = 1_080, maxPixels = 4L * 1024L * 1024L),
    AVATAR(maxDimension = 256, maxPixels = 256L * 256L),
    PLAYLIST(maxDimension = 320, maxPixels = 1_000_000L),
}

/** Single bounded entry point for all server-provided artwork. */
internal object ArtworkDecoder {
    fun decode(
        bytes: ByteArray,
        profile: ArtworkProfile,
        decodeByteArray: (ByteArray, Int, Int, BitmapFactory.Options?) -> Bitmap? =
            { value, offset, length, options -> BitmapFactory.decodeByteArray(value, offset, length, options) },
    ): Bitmap? = BitmapSafety.decode(
        bytes = bytes,
        maxDimension = profile.maxDimension,
        maxPixels = profile.maxPixels,
        decodeByteArray = decodeByteArray,
    )
}
