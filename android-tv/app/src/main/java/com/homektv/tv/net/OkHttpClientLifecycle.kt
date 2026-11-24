package com.homektv.tv.net

import okhttp3.Cache
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
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
    private const val DISK_CACHE_MAX_BYTES = 32L * 1024L * 1024L
    @Volatile
    private var sharedDiskCache: Cache? = null
    private val cacheLock = Any()

    /**
     * Installs a bounded 32MB HTTP disk cache so 7-day artist avatars and 1-day
     * covers served with Cache-Control headers survive memory LRU eviction and restarts.
     */
    fun installDiskCache(cacheRootDir: File?): Cache? {
        if (cacheRootDir == null) return sharedDiskCache
        sharedDiskCache?.let { return it }
        return synchronized(cacheLock) {
            sharedDiskCache ?: runCatching {
                val dir = File(cacheRootDir, "ktv_http_cache")
                if (!dir.exists()) dir.mkdirs()
                Cache(dir, DISK_CACHE_MAX_BYTES)
            }.getOrNull().also { sharedDiskCache = it }
        }
    }

    internal fun installedDiskCache(): Cache? = sharedDiskCache

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun apiClient(): OkHttpClient = baseClient.newBuilder()
        .apply { sharedDiskCache?.let { cache(it) } }
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Idempotent read/browse client with generous 15s read timeout and safe connection retry. */
    fun browseReadClient(): OkHttpClient = baseClient.newBuilder()
        .apply { sharedDiskCache?.let { cache(it) } }
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
