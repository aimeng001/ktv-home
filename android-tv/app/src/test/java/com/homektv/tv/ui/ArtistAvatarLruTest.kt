package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistAvatarLruTest {

    @Test
    fun boundedLru_enforcesByteCapacity() {
        val cache = RecommendationCoverCache<ByteArray>(
            capacity = 10,
            maxBytes = 300L,
            weight = { it.size.toLong() },
        )

        val item1 = ByteArray(100)
        val item2 = ByteArray(100)
        val item3 = ByteArray(100)
        val item4 = ByteArray(100)

        cache.complete(1L, item1)
        cache.complete(2L, item2)
        cache.complete(3L, item3)
        assertEquals(300L, cache.byteSize())

        // Adding 4th item should evict oldest (item 1)
        cache.complete(4L, item4)
        assertEquals(300L, cache.byteSize())
        assertNull(cache.get(1L))
        assertNotNull(cache.get(2L))
        assertNotNull(cache.get(3L))
        assertNotNull(cache.get(4L))
    }

    @Test
    fun clear_resetsAllEntriesAndBytes() {
        val cache = RecommendationCoverCache<ByteArray>(
            capacity = 10,
            maxBytes = 1000L,
            weight = { it.size.toLong() },
        )
        cache.complete(1L, ByteArray(200))
        cache.complete(2L, ByteArray(300))
        cache.clear()

        assertEquals(0, cache.size())
        assertEquals(0L, cache.byteSize())
        assertNull(cache.get(1L))
    }

    @Test
    fun inSampleSize_enforces128pxTarget() {
        // A 1024x1024 source image should have sampleSize >= 8 to fit within 128px
        val sampleSize = BitmapSafety.calculateInSampleSize(
            width = 1024,
            height = 1024,
            maxDimension = 128,
            maxPixels = 128L * 128L,
        )
        assertTrue(sampleSize >= 8)
        assertTrue(1024 / sampleSize <= 128)
    }

    @Test
    fun artistInitialFallback_resolvesCleanInitial() {
        assertEquals("周", ArtistInitialResolver.resolve("周杰伦"))
        assertEquals("A", ArtistInitialResolver.resolve("Andy Lau"))
        assertEquals("#", ArtistInitialResolver.resolve(""))
        assertEquals("#", ArtistInitialResolver.resolve("   "))
    }

    @Test
    fun multiSubscriberQueue_dispatchesToAllPendingCallbacks() {
        val pending = mutableMapOf<Long, MutableList<(String?) -> Unit>>()
        val urlKey = "http://nas:8080/avatar.jpg".hashCode().toLong()

        var card1Received: String? = null
        var card2Received: String? = null

        // 卡片 1 注册
        pending.getOrPut(urlKey) { mutableListOf() }.add { card1Received = it }
        // 卡片 2 在在途时注册
        pending.getOrPut(urlKey) { mutableListOf() }.add { card2Received = it }

        assertEquals(2, pending[urlKey]?.size)

        // 异步下载解码完成后分发并移除
        val waiting = pending.remove(urlKey) ?: emptyList()
        waiting.forEach { it("avatar_bitmap_mock") }

        assertEquals("avatar_bitmap_mock", card1Received)
        assertEquals("avatar_bitmap_mock", card2Received)
        assertNull(pending[urlKey])
    }
}
