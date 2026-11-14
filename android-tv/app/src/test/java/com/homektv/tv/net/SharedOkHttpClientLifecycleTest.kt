package com.homektv.tv.net

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SharedOkHttpClientLifecycleTest {

    @Test
    fun closingNonOwningTransportCancelsOnlyOwnInFlightCallsAndPreservesSharedExecutor() = runBlocking {
        val sharedBase = SharedHttpClients.baseClient
        val hangSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val okSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val requestAccepted = CountDownLatch(1)

        val hangServerThread = Thread {
            runCatching {
                hangSocket.accept().use { client ->
                    BufferedReader(InputStreamReader(client.getInputStream())).readLine()
                    requestAccepted.countDown()
                    Thread.sleep(3_000L)
                }
            }
        }.apply { isDaemon = true; start() }

        val okServerThread = Thread {
            runCatching {
                okSocket.accept().use { client ->
                    BufferedReader(InputStreamReader(client.getInputStream())).readLine()
                    val body = """{"service":"home-ktv"}"""
                    val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n\r\n$body"
                    client.getOutputStream().write(response.toByteArray(StandardCharsets.UTF_8))
                    client.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        val ephemeral = KtvHttpTransport(
            serverHost = { "127.0.0.1:${hangSocket.localPort}" },
            callFactory = SharedHttpClients.transportClient(),
            ownsClientResources = false,
        )
        val survivor = KtvHttpTransport(
            serverHost = { "127.0.0.1:${okSocket.localPort}" },
            callFactory = SharedHttpClients.transportClient(),
            ownsClientResources = false,
        )

        try {
            val pending = async(Dispatchers.IO) { ephemeral.get("/api/health") }
            assertTrue("in-flight call should reach server before close", requestAccepted.await(3, TimeUnit.SECONDS))

            ephemeral.close()

            val cancelledResult = pending.await()
            assertTrue(
                "closing ephemeral transport must abort its own in-flight request quickly",
                cancelledResult is KtvApiResult.Failure,
            )
            assertFalse(
                "closing non-owning transport must NEVER shut down SharedHttpClients executor",
                sharedBase.dispatcher.executorService.isShutdown,
            )

            val survivorResult = survivor.get("/api/health")
            assertTrue(
                "sibling transport on shared pool must remain healthy after ephemeral close",
                survivorResult is KtvApiResult.Success<*>,
            )
        } finally {
            survivor.close()
            hangSocket.close()
            okSocket.close()
            hangServerThread.join(500)
            okServerThread.join(500)
        }
    }

    @Test
    fun ownedOkHttpTransportStillShutsDownDedicatedExecutorOnClose() {
        val dedicated = OkHttpClient.Builder().build()
        val transport = KtvHttpTransport(
            serverHost = { "127.0.0.1:9" },
            callFactory = dedicated,
            ownsClientResources = true,
        )

        transport.close()

        assertTrue(
            "owned client resources must still be shut down when transport closes",
            dedicated.dispatcher.executorService.isShutdown,
        )
    }

    @Test
    fun sharedClientsShareSingleConnectionPoolAndDispatcher() {
        val api = SharedHttpClients.apiClient()
        val transport = SharedHttpClients.transportClient()

        assertSame(SharedHttpClients.baseClient.connectionPool, api.connectionPool)
        assertSame(SharedHttpClients.baseClient.connectionPool, transport.connectionPool)
        assertSame(SharedHttpClients.baseClient.dispatcher, api.dispatcher)
        assertSame(SharedHttpClients.baseClient.dispatcher, transport.dispatcher)
        assertFalse(SharedHttpClients.baseClient.dispatcher.executorService.isShutdown)
    }
}
