package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerActivitySessionContractTest {
    @Test
    fun controllerSessionChangesStartAReplacedActivityInsteadOfKeepingViewModelStore() {
        val source = locate("src/main/java/com/homektv/tv/ui/ControllerActivity.kt").readText()

        assertFalse(source.contains("recreate()"))
        assertTrue(source.contains("ControllerActivity::class.java"))
        assertTrue(source.contains("finish()"))
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
