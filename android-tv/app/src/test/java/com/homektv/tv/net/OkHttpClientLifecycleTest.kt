package com.homektv.tv.net

import okhttp3.OkHttpClient
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
}
