package com.homektv.tv.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LanDiscoveryLifecycleTest {

    @Test
    fun awaitValidationsWithinGracePeriodCompletesFastJobsAndCancelsStragglers() = runBlocking {
        val tracker = MdnsValidationTracker()
        val completed = AtomicBoolean(false)
        val stragglerCancelled = CompletableDeferred<Boolean>()

        val fastJob = launch {
            delay(20)
            completed.set(true)
        }
        val slowJob = launch {
            try {
                delay(5_000)
            } finally {
                stragglerCancelled.complete(true)
            }
        }
        tracker.track(fastJob)
        tracker.track(slowJob)

        tracker.awaitAndCancelRemaining(gracePeriodMs = 150)

        assertTrue("fast validation job should complete within grace period", completed.get())
        assertTrue("slow validation straggler should be cancelled on exit", stragglerCancelled.await())
        assertEquals(0, tracker.activeCount())
    }

    @Test
    fun lanDiscoverySafelyHandlesMissingNsdAwaitsValidationsAndCancelsStaleVerifyJobsInSetup() {
        val discoverySource = locate("src/main/java/com/homektv/tv/net/LanDiscovery.kt").readText()
        val mdnsMethod = discoverySource.substringAfter("private suspend fun discoverMdns")
            .substringBefore("private suspend fun discoverUdp")

        assertTrue(
            "discoverMdns must cast NSD_SERVICE safely with as? NsdManager",
            mdnsMethod.contains("as? NsdManager"),
        )
        assertTrue(
            "discoverMdns must track and await in-flight validation jobs before returning results",
            mdnsMethod.contains("MdnsValidationTracker") &&
                mdnsMethod.contains("awaitAndCancelRemaining("),
        )
        assertFalse(
            "discoverMdns must not allocate an untracked CoroutineScope(coroutineContext) wrapper",
            mdnsMethod.contains("CoroutineScope(coroutineContext)"),
        )
        assertTrue(
            "discoverMdns must guard multicastLock release",
            mdnsMethod.contains("runCatching { multicastLock.release() }"),
        )

        val setupSource = locate("src/main/java/com/homektv/tv/ui/SetupActivity.kt").readText()
        val verifyMethod = setupSource.substringAfter("private fun verifyAndConnect(")
            .substringBefore("private fun validateManualHost")
        val destroyMethod = setupSource.substringAfter("override fun onDestroy()")
            .substringBefore("private fun startScan()")

        assertTrue(
            "verifyAndConnect must cancel any prior in-flight verification job",
            verifyMethod.contains("verifyJob?.cancel()") &&
                verifyMethod.contains("verifyJob = lifecycleScope.launch"),
        )
        assertTrue(
            "SetupActivity.onDestroy must cancel verifyJob",
            destroyMethod.contains("verifyJob?.cancel()"),
        )
    }

    private fun locate(relativePath: String): File {
        val candidates = sequenceOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath")
    }
}
