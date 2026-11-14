package com.homektv.tv.net

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Releases the resources owned by a client that is scoped to an Activity or
 * another short-lived component.
 */
internal fun OkHttpClient.closeResources() {
    dispatcher.cancelAll()
    connectionPool.evictAll()
    runCatching { cache?.close() }
    dispatcher.executorService.shutdown()
}

/**
 * Tracks active OkHttp calls owned by a single client/transport wrapper so
 * closing that wrapper cancels only its own in-flight calls without tearing
 * down a shared Dispatcher/ConnectionPool.
 */
internal class ActiveCallRegistry {
    private val active = ConcurrentHashMap.newKeySet<Call>()

    fun register(call: Call): Call {
        active.add(call)
        return call
    }

    fun unregister(call: Call) {
        active.remove(call)
    }

    fun cancelAll() {
        val snapshot = active.toList()
        snapshot.forEach { call ->
            runCatching { call.cancel() }
            active.remove(call)
        }
    }
}

/**
 * Process-wide shared OkHttp pool and base client for short-lived API and
 * controller transports. Derived instances MUST NOT shut down the shared
 * dispatcher or connection pool when closed.
 */
internal object SharedHttpClients {
    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun apiClient(): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun transportClient(): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        // A timed-out write has an unknown server outcome. The caller must
        // decide whether to retry after refreshing authoritative state.
        .retryOnConnectionFailure(false)
        .build()
}
