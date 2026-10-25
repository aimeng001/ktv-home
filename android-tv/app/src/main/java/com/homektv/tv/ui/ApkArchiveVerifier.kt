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

internal interface ApkIdentityProvider {
    fun installedIdentity(packageName: String): ApkIdentity?
    fun archiveIdentity(apkFile: File): ApkIdentity?
}

object ApkArchiveVerifier {
    fun verifyApk(
        context: Context,
        apkFile: File,
        expectedVersionCode: Long,
    ): Boolean = verifyApk(
        apkFile = apkFile,
        expectedPackageName = context.packageName,
        expectedVersionCode = expectedVersionCode,
        identityProvider = AndroidApkIdentityProvider(context),
    )

    internal fun verifyApk(
        apkFile: File,
        expectedPackageName: String,
        expectedVersionCode: Long,
        identityProvider: ApkIdentityProvider,
    ): Boolean {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0) return false
        val installedSignatures = runCatching {
            identityProvider.installedIdentity(expectedPackageName)
        }.getOrNull() ?: return false
        if (installedSignatures.signatureDigests.isEmpty()
            || installedSignatures.currentSignatureDigests.isEmpty()
        ) return false

        val archive = runCatching {
            identityProvider.archiveIdentity(apkFile)
        }.getOrNull() ?: return false

        return ApkArchivePolicy.verify(
            expectedPackageName = expectedPackageName,
            expectedVersionCode = expectedVersionCode,
            expectedSignatures = installedSignatures.signatureDigests,
            expectedCurrentSignatures = installedSignatures.currentSignatureDigests,
            actual = archive,
        )
    }
}

private class AndroidApkIdentityProvider(private val context: Context) : ApkIdentityProvider {
    private val packageManager = context.packageManager
    private val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    override fun installedIdentity(packageName: String): ApkIdentity =
        packageManager.getPackageInfo(packageName, signingFlags).toApkIdentity()

    override fun archiveIdentity(apkFile: File): ApkIdentity? =
        packageManager.getPackageArchiveInfo(apkFile.absolutePath, signingFlags)?.toApkIdentity()

    private fun PackageInfo.toApkIdentity(): ApkIdentity {
        val actualVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
        val signatures = extractSignatures(this)
        return ApkIdentity(
            packageName = packageName ?: "",
            versionCode = actualVersionCode,
            signatureDigests = signatures.history,
            currentSignatureDigests = signatures.current,
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
