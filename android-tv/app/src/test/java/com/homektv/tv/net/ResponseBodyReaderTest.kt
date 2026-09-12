package com.homektv.tv.net

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseBodyReaderTest {
    @Test
    fun `chunked input is accepted at the limit`() {
        val bytes = byteArrayOf(1, 2, 3)

        assertArrayEquals(bytes, ResponseBodyReader.readBytes(ByteArrayInputStream(bytes), 3))
    }

    @Test
    fun `chunked input is rejected as soon as it exceeds the limit`() {
        val bytes = ByteArray(4) { it.toByte() }

        assertNull(ResponseBodyReader.readBytes(ByteArrayInputStream(bytes), 3))
    }

    @Test
    fun `text reader preserves utf8 while applying byte limit`() {
        assertEquals("你好", ResponseBodyReader.readBytes(
            ByteArrayInputStream("你好".toByteArray()), 6,
        )?.toString(Charsets.UTF_8))
    }
}
