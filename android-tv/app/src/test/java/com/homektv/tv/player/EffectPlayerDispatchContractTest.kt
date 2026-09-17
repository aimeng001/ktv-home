package com.homektv.tv.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectPlayerDispatchContractTest {

    @Test
    fun audioTrackCreationAndBlockingWriteAreDispatchedOffMainThread() {
        val source = locate("EffectPlayer.kt").readText()

        assertTrue(source.contains("Executors.newSingleThreadExecutor"))
        assertTrue(source.contains("io.execute"))
        assertTrue(source.contains(".write(sound.samples"))
    }

    private fun locate(name: String): File {
        val candidates = sequenceOf(
            File("src/main/java/com/homektv/tv/player/$name"),
            File("android-tv/app/src/main/java/com/homektv/tv/player/$name"),
            File("../app/src/main/java/com/homektv/tv/player/$name"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $name from ${File(".").absolutePath}")
    }
}
