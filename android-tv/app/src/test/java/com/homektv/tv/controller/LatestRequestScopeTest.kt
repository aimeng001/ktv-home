package com.homektv.tv.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRequestScopeTest {
    @Test
    fun replacingARequestCancelsThePreviousJob() = runBlocking {
        val scope = LatestRequestScope(CoroutineScope(Dispatchers.Unconfined))
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val first = scope.launch {
            firstStarted.complete(Unit)
            try {
                delay(Long.MAX_VALUE)
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        firstStarted.await()

        val second = scope.launch { Unit }
        firstCancelled.await()

        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        scope.cancel()
    }
}
