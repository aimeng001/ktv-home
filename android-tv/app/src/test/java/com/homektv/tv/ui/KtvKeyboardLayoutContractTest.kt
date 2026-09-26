package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKeyboardLayoutContractTest {
    @Test
    fun qwertyAlphabetUsesSevenColumnsLikeSelectedSearchDesign() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()
        val renderer = source.substringAfter("private fun renderQwertyKeys")
            .substringBefore("private fun setupActions")

        assertTrue(renderer.contains("grid.columnCount = 7"))
        assertTrue(renderer.contains("('A'..'Z').forEach"))
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath")
    }
}
