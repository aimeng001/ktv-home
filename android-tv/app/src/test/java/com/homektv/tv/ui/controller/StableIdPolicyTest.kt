package com.homektv.tv.ui.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableIdPolicyTest {
    @Test
    fun javaHashCodeCollisionStringsDoNotCollideInStableId() {
        // "Aa" and "BB" have the exact same 32-bit String.hashCode() == 2112
        assertEquals("Aa".hashCode(), "BB".hashCode())

        val id1 = StableIdPolicy.hash64(-1_000_000_000L, "Aa")
        val id2 = StableIdPolicy.hash64(-1_000_000_000L, "BB")

        assertNotEquals("64-bit StableId must not collide on 32-bit hash collision pairs", id1, id2)
    }

    @Test
    fun stableIdIsDeterministic() {
        val id1 = StableIdPolicy.hash64(-2_000_000_000L, "流行")
        val id2 = StableIdPolicy.hash64(-2_000_000_000L, "流行")
        assertEquals(id1, id2)
    }
}
