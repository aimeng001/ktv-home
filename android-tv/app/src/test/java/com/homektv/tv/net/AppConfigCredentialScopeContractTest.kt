package com.homektv.tv.net

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigCredentialScopeContractTest {
    @Test
    fun forgettingServerClearsIdentityAndHostCredentialScopes() {
        val source = locate("src/main/java/com/homektv/tv/net/AppConfig.kt").readText()
        val credentialStore = locate("src/main/java/com/homektv/tv/net/CredentialStore.kt").readText()

        assertTrue(source.contains("scopes.forEach(credentialStore::clearAllScopes)"))
        assertTrue(source.contains("remove(KEY_HOST)"))
        assertTrue(source.contains("fun migrateLegacyScope(from: SavedServer, to: SavedServer): Boolean"))
        assertTrue(source.contains("remove(oldPendingKey)"))
        assertTrue(!source.contains("migrateLegacyScope(existing, selected)"))
        assertTrue(credentialStore.contains("fun clearAllScopes"))
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
