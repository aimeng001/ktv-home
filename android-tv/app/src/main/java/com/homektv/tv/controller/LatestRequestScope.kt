package com.homektv.tv.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the latest read request for one UI domain.
 *
 * Replacing a query cancels the previous coroutine and therefore propagates
 * cancellation into the OkHttp call. The server response is still guarded by
 * the caller's query/key check because a response may already be completing.
 */
internal class LatestRequestScope(private val owner: CoroutineScope) {
    private var current: Job? = null

    @Synchronized
    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        current?.cancel()
        return owner.launch(block = block).also { current = it }
    }

    @Synchronized
    fun cancel() {
        current?.cancel()
        current = null
    }
}
