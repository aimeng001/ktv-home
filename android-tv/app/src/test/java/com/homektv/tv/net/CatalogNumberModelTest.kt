package com.homektv.tv.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogNumberModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun listAndDetailKeepTenDigitCatalogNumbersAsStrings() {
        val song = json.decodeFromString<SongDto>("""
            {"id": 42, "title": "Song", "artist": "Artist", "catalogNumber": "0010000750"}
        """.trimIndent())
        val detail = json.decodeFromString<SongDetail>("""
            {"id": 42, "title": "Song", "artist": "Artist", "catalogNumber": "0010000750"}
        """.trimIndent())

        assertEquals("0010000750", song.catalogNumber)
        assertEquals("0010000750", detail.catalogNumber)
    }
}
