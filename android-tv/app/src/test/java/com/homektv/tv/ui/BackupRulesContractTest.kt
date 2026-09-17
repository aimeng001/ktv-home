package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRulesContractTest {
    @Test
    fun manifestDeclaresBothModernAndLegacyBackupRules() {
        val manifest = locate("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/full_backup_content\""))
    }

    @Test
    fun backupRulesExcludeSharedPreferencesFromCloudAndDeviceTransfer() {
        val modern = locate("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(modern.contains("<cloud-backup"))
        assertTrue(modern.contains("<device-transfer"))
        assertTrue(modern.contains("<exclude domain=\"sharedpref\" path=\".\""))
        assertTrue(modern.contains("<exclude domain=\"file\" path=\".\""))
        assertTrue(!modern.contains("domain=\"cache\""))

        val legacy = locate("src/main/res/xml/full_backup_content.xml").readText()
        assertTrue(legacy.contains("<exclude domain=\"sharedpref\" path=\".\""))
        assertTrue(legacy.contains("<exclude domain=\"file\" path=\".\""))
        assertTrue(!legacy.contains("domain=\"cache\""))
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
