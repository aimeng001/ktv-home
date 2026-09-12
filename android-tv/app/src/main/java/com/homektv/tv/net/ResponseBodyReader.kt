package com.homektv.tv.net

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Reads HTTP bodies with a hard cap even when the peer omits Content-Length. */
internal object ResponseBodyReader {
    const val MAX_BINARY_BYTES = 10L * 1024L * 1024L
    const val MAX_TEXT_BYTES = 4L * 1024L * 1024L
    const val MAX_APK_BYTES = 512L * 1024L * 1024L

    fun readBytes(body: ResponseBody?, maxBytes: Long = MAX_BINARY_BYTES): ByteArray? {
        if (body == null || body.contentLength() > maxBytes) return null
        return body.byteStream().use { readBytes(it, maxBytes) }
    }

    fun readText(body: ResponseBody?, maxBytes: Long = MAX_TEXT_BYTES): String? =
        readBytes(body, maxBytes)?.toString(Charsets.UTF_8)

    fun readBytes(input: InputStream, maxBytes: Long): ByteArray? {
        if (maxBytes <= 0L || maxBytes > Int.MAX_VALUE) return null
        val limit = maxBytes.toInt()
        val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun copyTo(body: ResponseBody, output: OutputStream, maxBytes: Long): Long? {
        if (body.contentLength() > maxBytes) return null
        return body.byteStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) return@use null
                output.write(buffer, 0, count)
            }
            total
        }
    }
}
