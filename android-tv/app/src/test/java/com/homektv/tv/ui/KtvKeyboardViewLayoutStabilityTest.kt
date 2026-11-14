package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKeyboardViewLayoutStabilityTest {

    @Test
    fun layoutChangeListenerDefersResizeToAvoidSecondLayoutPass() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()

        // Must guard against synchronous requestLayout during onLayoutChange
        assertTrue(
            "KtvKeyboardView must post resize operations from onLayoutChange",
            source.contains("post {") && source.contains("resizeGridRows"),
        )

        // Must guard layoutParams modification with height check
        assertTrue(
            "resizeGridRows must check height before re-assigning layoutParams to avoid layout churn",
            source.contains("if (button.layoutParams.height != rowHeight)"),
        )
    }

    private fun locate(relativePath: String): File {
        var current = File(System.getProperty("user.dir").orEmpty())
        repeat(6) {
            val candidate = File(current, "android-tv/app/$relativePath")
            if (candidate.isFile) return candidate
            val candidateDirect = File(current, relativePath)
            if (candidateDirect.isFile) return candidateDirect
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
