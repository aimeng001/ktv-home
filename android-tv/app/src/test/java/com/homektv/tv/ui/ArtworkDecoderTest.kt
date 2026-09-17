package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkDecoderTest {
    @Test
    fun profilesKeepArtworkBoundsExplicit() {
        assertEquals(256, ArtworkProfile.AVATAR.maxDimension)
        assertEquals(256L * 256L, ArtworkProfile.AVATAR.maxPixels)
        assertEquals(1_080, ArtworkProfile.QR.maxDimension)
        assertEquals(320, ArtworkProfile.PLAYLIST.maxDimension)
        assertEquals(1_000_000L, ArtworkProfile.PLAYLIST.maxPixels)
    }

    @Test
    fun decoderRejectsOversizedInputBeforeBitmapFactory() {
        var decodeCalls = 0
        val result = ArtworkDecoder.decode(
            bytes = ByteArray((BitmapSafety.MAX_INPUT_BYTES + 1L).toInt()),
            profile = ArtworkProfile.COVER,
            decodeByteArray = { _, _, _, _ ->
                decodeCalls++
                null
            },
        )

        assertNull(result)
        assertEquals(0, decodeCalls)
    }
}
