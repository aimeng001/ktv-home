package com.homektv.tv.net

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpClientLifecycleTest {

    @Test
    fun closeResourcesStopsDispatcherAndReleasesClientResources() {
        val client = OkHttpClient()

        assertFalse(client.dispatcher.executorService.isShutdown)

        client.closeResources()

        assertTrue(client.dispatcher.executorService.isShutdown)
    }

    @Test
    fun sharedHttpClientsSeparateReadRetryPolicyFromNonIdempotentWriteClient() {
        val readClient = SharedHttpClients.browseReadClient()
        val writeClient = SharedHttpClients.transportClient()

        assertTrue("只读浏览客户端必须开启连接失败安全重试", readClient.retryOnConnectionFailure)
        assertEquals(15_000, readClient.readTimeoutMillis)
        assertFalse("写操作客户端必须禁用连接重试以防重复点歌/切歌", writeClient.retryOnConnectionFailure)
        assertEquals(8_000, writeClient.readTimeoutMillis)
    }
}
