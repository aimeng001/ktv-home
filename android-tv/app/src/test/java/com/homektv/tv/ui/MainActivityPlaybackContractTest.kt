package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPlaybackContractTest {
    @Test
    fun productionPlaybackUsesRetryingCoordinatorForSourceResolution() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()

        assertTrue(main.contains("PlaybackCoordinator("))
        assertTrue(main.contains("playbackCoordinator.replace("))
        assertTrue(main.contains("PlaybackReplacementRequest(queueId"))
        assertTrue(main.contains("source = object : PlaybackSource"))
    }

    @Test
    fun videoMediaTypeRemainsDeclaredWithoutResolutionMetadata() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()

        assertTrue(main.contains("val hasVideoDeclared = !snapshot.playing?.song?.mediaType.equals(\"AUDIO\", ignoreCase = true)"))
        assertTrue(!main.contains("&& !file.resolution.isNullOrBlank()"))
        assertTrue(main.contains("error.code == \"FILE_NOT_FOUND\" || error.code == \"NO_VALID_FILE\""))
    }

    @Test
    fun sameQueueVocalChangeDoesNotReloadMedia() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()

        assertTrue(main.contains("if (playing.queueId == currentQueueId && loadedQueueId == playing.queueId)"))
        assertTrue(main.contains("eng.setVocalMode(snapshot.vocalMode, accompanimentTrackIndex, audioTrackCount, currentAudioLayout)"))
        assertTrue(main.contains("if (playbackSeekGate.shouldApply(snapshot.seekSequence))"))
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
