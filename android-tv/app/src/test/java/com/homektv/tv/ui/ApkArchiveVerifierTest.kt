package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkArchiveVerifierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private companion object {
        fun installedIdentity() = ApkIdentity(
            packageName = "com.homektv.tv",
            versionCode = 100L,
            signatureDigests = listOf("installed-signer"),
        )

        fun candidateIdentity() = installedIdentity()
    }

    @Test
    fun missingDirectoryAndEmptyFilesAreRejectedBeforeIdentityReads() {
        val provider = FakeIdentityProvider()
        val missing = File(temporaryFolder.root, "missing.apk")
        val directory = temporaryFolder.newFolder("apk-directory")
        val empty = temporaryFolder.newFile("empty.apk")

        assertFalse(verify(missing, provider))
        assertFalse(verify(directory, provider))
        assertFalse(verify(empty, provider))
        assertEquals(0, provider.installedCalls)
        assertEquals(0, provider.archiveCalls)
    }

    @Test
    fun installedIdentityFailureOrAbsenceStopsBeforeArchiveRead() {
        val apk = nonEmptyApk()
        val thrown = FakeIdentityProvider(installedFailure = IllegalStateException("package manager failed"))
        assertFalse(verify(apk, thrown))
        assertEquals(1, thrown.installedCalls)
        assertEquals(0, thrown.archiveCalls)

        val missing = FakeIdentityProvider(installed = null)
        assertFalse(verify(apk, missing))
        assertEquals(1, missing.installedCalls)
        assertEquals(0, missing.archiveCalls)
    }

    @Test
    fun installedIdentityRequiresBothSignerHistoryAndCurrentSigners() {
        val apk = nonEmptyApk()
        val noHistory = FakeIdentityProvider(installed = installedIdentity().copy(signatureDigests = emptyList()))
        assertFalse(verify(apk, noHistory))
        assertEquals(0, noHistory.archiveCalls)

        val noCurrent = FakeIdentityProvider(
            installed = installedIdentity().copy(currentSignatureDigests = emptyList()),
        )
        assertFalse(verify(apk, noCurrent))
        assertEquals(0, noCurrent.archiveCalls)
    }

    @Test
    fun archiveIdentityFailureOrAbsenceIsRejected() {
        val apk = nonEmptyApk()
        val thrown = FakeIdentityProvider(archiveFailure = IllegalArgumentException("bad archive"))
        assertFalse(verify(apk, thrown))
        assertEquals(1, thrown.archiveCalls)

        val missing = FakeIdentityProvider(archived = null)
        assertFalse(verify(apk, missing))
        assertEquals(1, missing.archiveCalls)
    }

    @Test
    fun matchingIdentityIsAcceptedAndReadersReceiveRequestedInputs() {
        val apk = nonEmptyApk()
        val provider = FakeIdentityProvider()

        assertTrue(verify(apk, provider))
        assertEquals("com.homektv.tv", provider.installedPackageName)
        assertEquals(apk.absolutePath, provider.archivePath)
    }

    @Test
    fun candidatePackageAndVersionMustMatchTheInstalledIdentity() {
        val apk = nonEmptyApk()
        val wrongPackage = FakeIdentityProvider(archived = candidateIdentity().copy(packageName = "com.other.app"))
        assertFalse(verify(apk, wrongPackage))

        val wrongVersion = FakeIdentityProvider(archived = candidateIdentity().copy(versionCode = 99L))
        assertFalse(verify(apk, wrongVersion))
    }

    private fun nonEmptyApk(): File = temporaryFolder.newFile("candidate-${System.nanoTime()}.apk").apply {
        writeText("simulated apk bytes")
    }

    private fun verify(file: File, provider: ApkIdentityProvider): Boolean =
        ApkArchiveVerifier.verifyApk(file, "com.homektv.tv", 100L, provider)

    private class FakeIdentityProvider(
        private val installed: ApkIdentity? = installedIdentity(),
        private val archived: ApkIdentity? = candidateIdentity(),
        private val installedFailure: RuntimeException? = null,
        private val archiveFailure: RuntimeException? = null,
    ) : ApkIdentityProvider {
        var installedCalls = 0
            private set
        var archiveCalls = 0
            private set
        var installedPackageName: String? = null
            private set
        var archivePath: String? = null
            private set

        override fun installedIdentity(packageName: String): ApkIdentity? {
            installedCalls++
            installedPackageName = packageName
            installedFailure?.let { throw it }
            return installed
        }

        override fun archiveIdentity(apkFile: File): ApkIdentity? {
            archiveCalls++
            archivePath = apkFile.absolutePath
            archiveFailure?.let { throw it }
            return archived
        }
    }
}
