package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainArtworkPipelineContractTest {
    @Test
    fun mainActivityDoesNotBypassBoundedArtworkDecoder() {
        val source = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt")
        val text = source.readText()

        assertFalse(text.contains("BitmapFactory.decodeByteArray"))
        assertTrue(text.contains("ArtworkDecoder.decode"))
    }

    @Test
    fun controllerFragmentDoesNotDecodePlaylistArtworkSynchronouslyOnMain() {
        val source = locate("src/main/java/com/homektv/tv/ui/controller/ControllerFragment.kt").readText()

        assertFalse(source.contains("setImageBitmap(ArtworkDecoder.decode"))
        assertTrue(source.contains("withContext(Dispatchers.Default)"))
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val candidates = sequence {
            yield(workingDirectory.resolve(relativePath))
            yield(workingDirectory.resolve("app/$relativePath"))
            yield(workingDirectory.resolve("../$relativePath"))
            yield(workingDirectory.resolve("../../$relativePath"))
        }
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
