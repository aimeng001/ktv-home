package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkArchivePolicyTest {

    private val expectedPackage = "com.homektv.tv"
    private val expectedVersion = 100L
    private val expectedSigs = listOf("sig_sha256_hash_abc")

    @Test
    fun nullActualReturnsFalse() {
        assertFalse(
            ApkArchivePolicy.verify(
                expectedPackageName = expectedPackage,
                expectedVersionCode = expectedVersion,
                expectedSignatures = expectedSigs,
                actual = null,
            ),
        )
    }

    @Test
    fun packageNameMismatchReturnsFalse() {
        val actual = ApkIdentity(
            packageName = "com.malicious.app",
            versionCode = expectedVersion,
            signatureDigests = expectedSigs,
        )
        assertFalse(
            ApkArchivePolicy.verify(
                expectedPackageName = expectedPackage,
                expectedVersionCode = expectedVersion,
                expectedSignatures = expectedSigs,
                actual = actual,
            ),
        )
    }

    @Test
    fun versionCodeMismatchReturnsFalse() {
        val actual = ApkIdentity(
            packageName = expectedPackage,
            versionCode = 99L,
            signatureDigests = expectedSigs,
        )
        assertFalse(
            ApkArchivePolicy.verify(
                expectedPackageName = expectedPackage,
                expectedVersionCode = expectedVersion,
                expectedSignatures = expectedSigs,
                actual = actual,
            ),
        )
    }

    @Test
    fun signatureMismatchReturnsFalse() {
        val actual = ApkIdentity(
            packageName = expectedPackage,
            versionCode = expectedVersion,
            signatureDigests = listOf("wrong_signature_hash"),
        )
        assertFalse(
            ApkArchivePolicy.verify(
                expectedPackageName = expectedPackage,
                expectedVersionCode = expectedVersion,
                expectedSignatures = expectedSigs,
                actual = actual,
            ),
        )
    }

    @Test
    fun emptyActualSignaturesWhenExpectedNotEmptyReturnsFalse() {
        val actual = ApkIdentity(
            packageName = expectedPackage,
            versionCode = expectedVersion,
            signatureDigests = emptyList(),
        )
        assertFalse(
            ApkArchivePolicy.verify(
                expectedPackageName = expectedPackage,
                expectedVersionCode = expectedVersion,
                expectedSignatures = expectedSigs,
                actual = actual,
            ),
        )
    }

    @Test
    fun matchingPackageVersionAndSignaturesReturnsTrue() {
        val actual = ApkIdentity(
            packageName = expectedPackage,
            versionCode = expectedVersion,
            signatureDigests = listOf("sig_sha256_hash_abc"),
        )
        assertTrue(
            ApkArchivePolicy.verify(
                expectedPackageName = expectedPackage,
                expectedVersionCode = expectedVersion,
                expectedSignatures = expectedSigs,
                actual = actual,
            ),
        )
    }

    @Test
    fun matchingPackageAndVersionWhenExpectedSignaturesEmptyReturnsTrue() {
        val actual = ApkIdentity(
            packageName = expectedPackage,
            versionCode = expectedVersion,
            signatureDigests = listOf("any_signature"),
        )
        assertTrue(
            ApkArchivePolicy.verify(
                expectedPackageName = expectedPackage,
                expectedVersionCode = expectedVersion,
                expectedSignatures = emptyList(),
                actual = actual,
            ),
        )
    }
}
