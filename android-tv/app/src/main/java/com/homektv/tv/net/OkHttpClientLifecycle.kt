package com.homektv.tv.net

import okhttp3.OkHttpClient

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
