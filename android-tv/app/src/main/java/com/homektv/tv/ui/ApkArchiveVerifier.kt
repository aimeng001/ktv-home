package com.homektv.tv.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signatureDigests: List<String> = emptyList(),
    val currentSignatureDigests: List<String> = signatureDigests,
)

object ApkArchivePolicy {
    fun verify(
        expectedPackageName: String,
        expectedVersionCode: Long,
        expectedSignatures: List<String>,
        expectedCurrentSignatures: List<String> = expectedSignatures,
        actual: ApkIdentity?,
    ): Boolean {
        if (actual == null) return false
        if (actual.packageName != expectedPackageName) return false
        if (actual.versionCode != expectedVersionCode) return false
        if (expectedSignatures.isEmpty() || expectedCurrentSignatures.isEmpty()) return false
        if (actual.signatureDigests.isEmpty() || actual.currentSignatureDigests.isEmpty()) return false

        val expectedHistory = expectedSignatures.toSet()
        val expectedCurrent = expectedCurrentSignatures.toSet()
        val actualHistory = actual.signatureDigests.toSet()
        val actualCurrent = actual.currentSignatureDigests.toSet()
        if (!expectedHistory.containsAll(expectedCurrent)) return false
        if (!actualHistory.containsAll(actualCurrent)) return false

        // The same current signer is the common case. A forward rotation is
        // accepted only when the candidate's verified lineage carries the
        // currently installed signer; an old signer cannot be used after the
        // installed app has already moved forward.
        return actualCurrent == expectedCurrent ||
            (expectedCurrent.size == 1 && actualCurrent.size == 1 &&
                actualHistory.contains(expectedCurrent.single()))
    }
}

private data class ExtractedSignatures(
    val history: List<String>,
    val current: List<String>,
)

object ApkArchiveVerifier {
    fun verifyApk(
        context: Context,
        apkFile: File,
        expectedVersionCode: Long,
    ): Boolean {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0) return false
        val pm = context.packageManager
        val expectedPackageName = context.packageName

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val installedSignatures = runCatching {
            val installed = pm.getPackageInfo(expectedPackageName, flags)
            extractSignatures(installed)
        }.getOrNull() ?: return false
        if (installedSignatures.history.isEmpty() || installedSignatures.current.isEmpty()) return false

        val archive = runCatching {
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        }.getOrNull() ?: return false

        val actualVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }

        val actualSignatures = extractSignatures(archive)

        val actualIdentity = ApkIdentity(
            packageName = archive.packageName ?: "",
            versionCode = actualVersionCode,
            signatureDigests = actualSignatures.history,
            currentSignatureDigests = actualSignatures.current,
        )

        return ApkArchivePolicy.verify(
            expectedPackageName = expectedPackageName,
            expectedVersionCode = expectedVersionCode,
            expectedSignatures = installedSignatures.history,
            expectedCurrentSignatures = installedSignatures.current,
            actual = actualIdentity,
        )
    }

    private fun extractSignatures(packageInfo: PackageInfo): ExtractedSignatures {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return ExtractedSignatures(emptyList(), emptyList())
            val current = signingInfo.apkContentsSigners?.map { signatureToSha256(it.toByteArray()) }.orEmpty()
            val history = if (signingInfo.hasMultipleSigners()) {
                current
            } else {
                signingInfo.signingCertificateHistory
                    ?.map { signatureToSha256(it.toByteArray()) }
                    .orEmpty()
            }
            return ExtractedSignatures(
                history = history,
                current = current,
            )
        } else {
            @Suppress("DEPRECATION")
            val signatures = packageInfo.signatures
                ?.map { signatureToSha256(it.toByteArray()) }
                .orEmpty()
            return ExtractedSignatures(history = signatures, current = signatures)
        }
    }

    private fun signatureToSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
