package com.homektv.tv.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaApiCancellationContractTest {

    @Test
    fun everyMediaApiCallUsesCancellableOkHttpExecution() {
        val source = locate("MediaApi.kt").readText()

        assertTrue(source.contains("suspendCancellableCoroutine"))
        assertTrue(source.contains("continuation.invokeOnCancellation { call.cancel() }"))
        assertTrue(source.contains("catch (cancelled: CancellationException)"))
        assertTrue(!source.contains(".execute()"))
    }

    @Test
    fun standbyFetchReturnsNoValueOnFailureInsteadOfSyntheticDefaults() {
        val source = locate("MediaApi.kt").readText()

        assertTrue(source.contains("suspend fun fetchStandbyContent(): StandbyContent?"))
        assertTrue(source.contains("if (!resp.isSuccessful) return@withContext null"))
    }

    private fun locate(name: String): File {
        val candidates = sequenceOf(
            File("src/main/java/com/homektv/tv/net/$name"),
            File("android-tv/app/src/main/java/com/homektv/tv/net/$name"),
            File("../app/src/main/java/com/homektv/tv/net/$name"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $name from ${File(".").absolutePath}")
    }
}
