package com.homektv.tv.ui

import org.junit.Assert.*
import org.junit.Test

class RecommendationCoverCacheTest {
    @Test
    fun `cache evicts oldest entry when exceeding capacity`() {
        val cache = RecommendationCoverCache<String>(capacity = 3)
        cache.complete(1L, "cov1")
        cache.complete(2L, "cov2")
        cache.complete(3L, "cov3")
        cache.complete(4L, "cov4")

        assertEquals(3, cache.size())
        assertNull(cache.get(1L))
        assertEquals("cov2", cache.get(2L))
        assertEquals("cov3", cache.get(3L))
        assertEquals("cov4", cache.get(4L))
    }

    @Test
    fun `failed load allows retry on next cycle`() {
        val cache = RecommendationCoverCache<String>(capacity = 3)
        assertTrue(cache.tryStartLoad(1L))
        assertFalse(cache.tryStartLoad(1L)) // in flight

        cache.fail(1L)
        assertNull(cache.get(1L))
        assertTrue(cache.tryStartLoad(1L)) // retry allowed
    }

    @Test
    fun `clear resets cache and in-flight tracking`() {
        val cache = RecommendationCoverCache<String>(capacity = 3)
        cache.complete(1L, "cov1")
        assertTrue(cache.tryStartLoad(2L))
        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get(1L))
        assertTrue(cache.tryStartLoad(2L))
    }

    @Test
    fun `weighted cache evicts by bytes even below entry capacity`() {
        val cache = RecommendationCoverCache<String>(
            capacity = 10,
            maxBytes = 5,
            weight = { it.length.toLong() },
        )

        cache.complete(1L, "1234")
        cache.complete(2L, "12")

        assertNull(cache.get(1L))
        assertEquals("12", cache.get(2L))
        assertEquals(2L, cache.byteSize())
    }
}
