package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialMigrationTest {
    @Test
    fun legacyCredentialOnlyMigratesToTheOriginalServer() {
        assertTrue(shouldMigrateLegacyCredential("http://nas-a", "nas-a:8080"))
        assertTrue(shouldMigrateLegacyCredential("nas-a:8080/", "nas-a:8080"))
        assertFalse(shouldMigrateLegacyCredential("nas-a:8080", "nas-b:8080"))
        assertFalse(shouldMigrateLegacyCredential("nas-a:8080", null))
    }
}
