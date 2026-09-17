package com.homektv.tv.net

import java.io.Closeable
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaApiLifecycleTest {
    @Test
    fun mediaApiDeclaresCloseableOwnership() {
        assertTrue(Closeable::class.java.isAssignableFrom(MediaApi::class.java))
    }
}
