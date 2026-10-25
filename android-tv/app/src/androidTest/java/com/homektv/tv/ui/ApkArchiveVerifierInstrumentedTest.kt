package com.homektv.tv.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ApkArchiveVerifierInstrumentedTest {
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedApkWithMatchingPackageVersionAndSignerIsAccepted() {
        val context = targetContext
        val apk = copyInstalledApk(context)
        try {
            assertTrue(ApkArchiveVerifier.verifyApk(context, apk, installedVersionCode(context)))
            assertFalse(ApkArchiveVerifier.verifyApk(context, apk, installedVersionCode(context) + 1))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun missingInstalledPackageFailsClosed() {
        val context = targetContext
        val apk = copyInstalledApk(context)
        val missingPackageContext = object : ContextWrapper(context) {
            override fun getPackageName(): String = "com.homektv.missing"
        }
        try {
            assertFalse(ApkArchiveVerifier.verifyApk(missingPackageContext, apk, installedVersionCode(context)))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun missingDirectoryEmptyAndMalformedApksAreRejected() {
        val context = targetContext
        val cache = context.cacheDir
        val prefix = "apk-verifier-${System.nanoTime()}"
        val missing = File(cache, "$prefix-missing.apk")
        val directory = File(cache, "$prefix-directory")
        val empty = File(cache, "$prefix-empty.apk")
        val malformed = File(cache, "$prefix-malformed.apk")

        assertFalse(ApkArchiveVerifier.verifyApk(context, missing, installedVersionCode(context)))
        try {
            assertTrue(directory.mkdir())
            assertFalse(ApkArchiveVerifier.verifyApk(context, directory, installedVersionCode(context)))
            assertTrue(empty.createNewFile())
            assertFalse(ApkArchiveVerifier.verifyApk(context, empty, installedVersionCode(context)))
            malformed.writeText("not an Android package archive")
            assertFalse(ApkArchiveVerifier.verifyApk(context, malformed, installedVersionCode(context)))
        } finally {
            directory.delete()
            empty.delete()
            malformed.delete()
        }
    }

    private fun copyInstalledApk(context: Context): File {
        val destination = File(context.cacheDir, "apk-verifier-${System.nanoTime()}.apk")
        File(context.applicationInfo.sourceDir).copyTo(destination, overwrite = false)
        return destination
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
}
