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
)

object ApkArchivePolicy {
    fun verify(
        expectedPackageName: String,
        expectedVersionCode: Long,
        expectedSignatures: List<String>,
        actual: ApkIdentity?,
    ): Boolean {
        if (actual == null) return false
        if (actual.packageName != expectedPackageName) return false
        if (actual.versionCode != expectedVersionCode) return false
        if (expectedSignatures.isNotEmpty()) {
            if (actual.signatureDigests.isEmpty()) return false
            if (actual.signatureDigests.toSet() != expectedSignatures.toSet()) return false
        }
        return true
    }
}

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
        }.getOrDefault(emptyList())

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
            signatureDigests = actualSignatures,
        )

        return ApkArchivePolicy.verify(
            expectedPackageName = expectedPackageName,
            expectedVersionCode = expectedVersionCode,
            expectedSignatures = installedSignatures,
            actual = actualIdentity,
        )
    }

    private fun extractSignatures(packageInfo: PackageInfo): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptyList()
            val certs = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            return certs?.map { signatureToSha256(it.toByteArray()) } ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            return packageInfo.signatures?.map { signatureToSha256(it.toByteArray()) } ?: emptyList()
        }
    }

    private fun signatureToSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
