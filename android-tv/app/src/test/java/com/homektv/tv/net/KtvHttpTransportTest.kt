package com.homektv.tv.net

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import okio.Timeout

class KtvHttpTransportTest {
    @Test
    fun unconfiguredHostRejectsEveryRequestWithoutOpeningNetworkCalls() = runBlocking {
        var hostReads = 0
        val transport = transport({ hostReads++; " " })
        try {
            val results = listOf(
                transport.get("/songs"),
                transport.getBytes("/cover/1"),
                transport.post("/control", buildJsonObject { put("action", "pause") }),
                transport.delete("/queue/1"),
            )
            assertTrue(results.all { it is KtvApiResult.Failure })
            results.filterIsInstance<KtvApiResult.Failure>().forEach {
                assertEquals("SERVER_NOT_CONFIGURED", it.error.code)
            }
            assertEquals(4, hostReads)
        } finally {
            transport.close()
        }
    }

    @Test
    fun getPostAndDeleteSendNormalizedRequestsAndReturnSuccessfulBodies() = runBlocking {
        LocalHttpServer(List(3) { HttpResponse(200, "{\"ok\":true}".toByteArray()) }).use { server ->
            val transport = transport({ server.host })
            try {
                assertEquals(KtvApiResult.Success("{\"ok\":true}"), transport.get("/songs"))
                assertEquals(
                    KtvApiResult.Success("{\"ok\":true}"),
                    transport.post("control", buildJsonObject { put("action", "pause") }),
                )
                assertEquals(KtvApiResult.Success("{\"ok\":true}"), transport.delete("/queue/17"))
            } finally {
                transport.close()
            }

            val requests = server.awaitRequests()
            assertEquals(listOf("GET", "POST", "DELETE"), requests.map { it.method })
            assertEquals(listOf("/api/songs", "/api/control", "/api/queue/17"), requests.map { it.path })
            assertEquals("{\"action\":\"pause\"}", requests[1].body)
        }
    }

    @Test
    fun binarySuccessAndHttpFailureAreClassified() = runBlocking {
        val bytes = byteArrayOf(0, 1, 2, -1)
        LocalHttpServer(listOf(HttpResponse(200, bytes))).use { server ->
            val transport = transport({ server.host })
            try {
                val result = transport.getBytes("/cover/1")
                assertTrue(result is KtvApiResult.Success)
                assertArrayEquals(bytes, (result as KtvApiResult.Success).value)
            } finally {
                transport.close()
            }
        }

        LocalHttpServer(listOf(HttpResponse(503, "unavailable".toByteArray()))).use { server ->
            val transport = transport({ server.host })
            try {
                val result = transport.getBytes("/cover/1") as KtvApiResult.Failure
                assertEquals(KtvApiErrorKind.HTTP, result.error.kind)
                assertEquals("HTTP_503", result.error.code)
                assertEquals(503, result.error.status)
            } finally {
                transport.close()
            }
        }

        LocalHttpServer(
            listOf(HttpResponse(200, byteArrayOf(), ResponseBodyReader.MAX_BINARY_BYTES + 1)),
        ).use { server ->
            val transport = transport({ server.host })
            try {
                val result = transport.getBytes("/large-cover") as KtvApiResult.Failure
                assertEquals(KtvApiErrorKind.PAYLOAD_TOO_LARGE, result.error.kind)
                assertEquals("PAYLOAD_TOO_LARGE", result.error.code)
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun oversizedTextResponseReturnsBoundedPayloadError() = runBlocking {
        LocalHttpServer(
            listOf(HttpResponse(200, byteArrayOf(), ResponseBodyReader.MAX_TEXT_BYTES + 1)),
        ).use { server ->
            val transport = transport({ server.host })
            try {
                val result = transport.get("/large") as KtvApiResult.Failure
                assertEquals(KtvApiErrorKind.PAYLOAD_TOO_LARGE, result.error.kind)
                assertEquals("PAYLOAD_TOO_LARGE", result.error.code)
                assertEquals(200, result.error.status)
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun refusedConnectionReturnsRetryableNetworkFailure() = runBlocking {
        val closedSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val host = "127.0.0.1:${closedSocket.localPort}"
        closedSocket.close()
        val transport = transport({ host })
        try {
            val result = transport.get("/songs") as KtvApiResult.Failure
            assertEquals(KtvApiErrorKind.NETWORK, result.error.kind)
            assertEquals("NETWORK_ERROR", result.error.code)
            val binaryResult = transport.getBytes("/cover/1") as KtvApiResult.Failure
            assertEquals(KtvApiErrorKind.NETWORK, binaryResult.error.kind)
            assertEquals("NETWORK_ERROR", binaryResult.error.code)
        } finally {
            transport.close()
        }
    }

    @Test
    fun cancellingTextAndBinaryRequestsCancelsTheUnderlyingOkHttpCall() = runBlocking {
        suspend fun assertCancellation(binary: Boolean) {
            BlockingHttpServer().use { server ->
                val transport = transport({ server.host })
                try {
                    val request = async(Dispatchers.IO) {
                        if (binary) transport.getBytes("/hang") else transport.get("/hang")
                    }
                    assertTrue("request should reach the local server", server.awaitRequest())
                    request.cancelAndJoin()
                    assertTrue("cancellation should close the HTTP connection", server.awaitDisconnect())
                } finally {
                    transport.close()
                }
            }
        }

        assertCancellation(binary = false)
        assertCancellation(binary = true)
    }

    @Test
    fun binaryInformationalStatusIsClassifiedAsHttpFailure() = runBlocking {
        val callFactory = ControlledCallFactory()
        val transport = KtvHttpTransport({ "127.0.0.1:1" }, callFactory)
        try {
            val request = async(Dispatchers.IO) { transport.getBytes("/cover/1") }
            callFactory.awaitCall().respond(199)

            val failure = request.await() as KtvApiResult.Failure
            assertEquals(KtvApiErrorKind.HTTP, failure.error.kind)
            assertEquals("HTTP_199", failure.error.code)
            assertEquals(199, failure.error.status)
        } finally {
            transport.close()
        }
    }

    @Test
    fun lateCallbacksAfterCancellationAreIgnoredForTextAndBinaryRequests() = runBlocking {
        suspend fun assertLateCallbackIgnored(binary: Boolean, respond: Boolean) {
            val callFactory = ControlledCallFactory()
            val transport = KtvHttpTransport({ "127.0.0.1:1" }, callFactory)
            try {
                val request = async(Dispatchers.IO) {
                    if (binary) transport.getBytes("/cover/1") else transport.get("/songs")
                }
                val call = callFactory.awaitCall()
                request.cancelAndJoin()

                if (respond) call.respond(200) else call.fail()
                assertTrue("cancellation should reach the call", call.isCanceled())
            } finally {
                transport.close()
            }
        }

        assertLateCallbackIgnored(binary = false, respond = true)
        assertLateCallbackIgnored(binary = true, respond = true)
    }

    private fun transport(serverHost: () -> String?) = KtvHttpTransport(
        serverHost,
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
    )

    private class ControlledCallFactory : Call.Factory {
        private val created = CountDownLatch(1)
        @Volatile private var currentCall: ControlledCall? = null

        override fun newCall(request: Request): Call = ControlledCall(request).also {
            currentCall = it
            created.countDown()
        }

        fun awaitCall(): ControlledCall {
            check(created.await(5, TimeUnit.SECONDS)) { "HTTP call was not created" }
            return checkNotNull(currentCall)
        }
    }

    private class ControlledCall(private val originalRequest: Request) : Call {
        private val enqueued = CountDownLatch(1)
        @Volatile private var callback: Callback? = null
        @Volatile private var canceled = false

        override fun request(): Request = originalRequest

        override fun execute(): Response = error("Synchronous execution is not used by KtvHttpTransport")

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            enqueued.countDown()
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = enqueued.count == 0L

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = ControlledCall(originalRequest)

        fun respond(code: Int) {
            check(enqueued.await(5, TimeUnit.SECONDS)) { "HTTP callback was not installed" }
            val response = Response.Builder()
                .request(originalRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("simulated")
                .body("simulated".toResponseBody())
                .build()
            checkNotNull(callback).onResponse(this, response)
        }

        fun fail() {
            check(enqueued.await(5, TimeUnit.SECONDS)) { "HTTP callback was not installed" }
            checkNotNull(callback).onFailure(this, IOException("simulated failure"))
        }
    }

    private data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val declaredContentLength: Long = body.size.toLong(),
    )

    private data class CapturedRequest(val method: String, val path: String, val body: String)

    private class LocalHttpServer(responses: List<HttpResponse>) : AutoCloseable {
        private val listener = ServerSocket(0, responses.size, InetAddress.getByName("127.0.0.1"))
        private val requests = mutableListOf<CapturedRequest>()
        private val worker = thread(isDaemon = true) {
            responses.forEach { response ->
                listener.accept().use { socket ->
                    val request = readRequest(socket)
                    synchronized(requests) { requests += request }
                    writeResponse(socket, response)
                }
            }
        }

        val host: String = "127.0.0.1:${listener.localPort}"

        fun awaitRequests(): List<CapturedRequest> {
            worker.join(TimeUnit.SECONDS.toMillis(5))
            check(!worker.isAlive) { "HTTP test server did not receive all requests" }
            return synchronized(requests) { requests.toList() }
        }

        override fun close() {
            listener.close()
            worker.join(TimeUnit.SECONDS.toMillis(1))
        }

        private fun readRequest(socket: Socket): CapturedRequest {
            val input = socket.getInputStream()
            val requestLine = readAsciiLine(input).split(' ')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input)
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(contentLength)
            var offset = 0
            while (offset < body.size) {
                val read = input.read(body, offset, body.size - offset)
                if (read < 0) break
                offset += read
            }
            check(offset == body.size) { "HTTP request body ended before Content-Length" }
            return CapturedRequest(
                method = requestLine[0],
                path = requestLine[1],
                body = body.toString(StandardCharsets.UTF_8),
            )
        }

        private fun readAsciiLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0 || next == '\n'.code) break
                if (next != '\r'.code) bytes.write(next)
            }
            return bytes.toString(StandardCharsets.US_ASCII)
        }

        private fun writeResponse(socket: Socket, response: HttpResponse) {
            val reason = if (response.status in 200..299) "OK" else "Failure"
            val headers = "HTTP/1.1 ${response.status} $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${response.declaredContentLength}\r\n" +
                "Connection: close\r\n\r\n"
            val output = socket.getOutputStream()
            output.write(headers.toByteArray(StandardCharsets.US_ASCII))
            output.write(response.body)
            output.flush()
        }
    }
}

private class BlockingHttpServer : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val requestSeen = CountDownLatch(1)
    private val disconnected = CountDownLatch(1)
    private val worker = thread(isDaemon = true) {
        try {
            listener.accept().use { socket ->
                val input = socket.getInputStream()
                readAsciiLine(input)
                while (readAsciiLine(input).isNotEmpty()) { }
                requestSeen.countDown()
                input.read()
                disconnected.countDown()
            }
        } catch (_: IOException) {
            disconnected.countDown()
        }
    }

    val host: String = "127.0.0.1:${listener.localPort}"

    fun awaitRequest(): Boolean = requestSeen.await(5, TimeUnit.SECONDS)

    fun awaitDisconnect(): Boolean = disconnected.await(5, TimeUnit.SECONDS)

    override fun close() {
        listener.close()
        worker.join(TimeUnit.SECONDS.toMillis(1))
    }

    private fun readAsciiLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
            if (next != '\r'.code) bytes.write(next)
        }
        return bytes.toString(StandardCharsets.US_ASCII)
    }
}
