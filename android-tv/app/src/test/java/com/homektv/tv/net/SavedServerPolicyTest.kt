package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedServerPolicyTest {
    private val idA = "550e8400-e29b-41d4-a716-446655440000"
    private val idB = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    @Test
    fun sameIdentityOnNewHostReplacesOldEndpoint() {
        val merged = SavedServerPolicy.merge(
            existing = listOf(
                SavedServer("10.0.0.10:8080", "客厅", idA),
                SavedServer("10.0.0.11:8080", "卧室", idB),
            ),
            incoming = SavedServer("10.0.0.25:8080", "客厅", idA.uppercase()),
        )

        assertEquals(
            listOf(
                SavedServer("10.0.0.25:8080", "客厅", idA),
                SavedServer("10.0.0.11:8080", "卧室", idB),
            ),
            merged,
        )
    }

    @Test
    fun sameHostDifferentIdentityReplacesOldServer() {
        val merged = SavedServerPolicy.merge(
            existing = listOf(SavedServer("10.0.0.10:8080", "旧", idA)),
            incoming = SavedServer("10.0.0.10:8080", "新", idB),
        )

        assertEquals(listOf(SavedServer("10.0.0.10:8080", "新", idB)), merged)
    }

    @Test
    fun differentIdentityAndHostAreBothRetained() {
        val existing = SavedServer("10.0.0.10:8080", "旧", idA)
        assertEquals(
            listOf(SavedServer("10.0.0.25:8080", "新", idB), existing),
            SavedServerPolicy.merge(listOf(existing), SavedServer("10.0.0.25:8080", "新", idB)),
        )
    }

    @Test
    fun unknownIdentityDeduplicatesOnlyByHost() {
        val existing = listOf(
            SavedServer("10.0.0.10:8080", "旧"),
            SavedServer("10.0.0.11:8080", "另一个"),
        )

        assertEquals(
            listOf(SavedServer("10.0.0.10:8080", "新"), existing[1]),
            SavedServerPolicy.merge(existing, SavedServer("10.0.0.10:8080", "新")),
        )
    }

    @Test
    fun resultIsBounded() {
        val existing = (1..10).map { SavedServer("10.0.0.$it:8080", "服务$it") }
        val merged = SavedServerPolicy.merge(existing, SavedServer("10.0.0.99:8080", "新"), maxEntries = 10)

        assertEquals(10, merged.size)
        assertEquals("10.0.0.99:8080", merged.first().hostPort)
    }
}
