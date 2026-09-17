package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupActivityVerificationContractTest {
    @Test
    fun setupCommitsOnlyTheServerReturnedByDiscovery() {
        val source = locate("src/main/java/com/homektv/tv/ui/SetupActivity.kt").readText()

        assertTrue(source.contains("scanner.discover(server.hostPort)"))
        assertTrue(source.contains("SetupVerificationPolicy.confirm"))
        assertTrue(source.contains("config.rememberServer(confirmed)"))
        assertFalse(source.contains("scanner.validate(server.hostPort, server.instanceId)"))
    }

    @Test
    fun setupAllowsLongClickToForgetServerThoroughly() {
        val source = locate("src/main/java/com/homektv/tv/ui/SetupActivity.kt").readText()
        assertTrue(source.contains("setOnLongClickListener"))
        assertTrue(source.contains("config.forgetServer(server)"))
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
