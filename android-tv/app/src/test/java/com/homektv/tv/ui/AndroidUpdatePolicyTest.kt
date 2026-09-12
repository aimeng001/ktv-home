package com.homektv.tv.ui

import com.homektv.tv.net.ApkPackageInfo
import com.homektv.tv.net.ReleaseInfo
import com.homektv.tv.net.TvPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUpdatePolicyTest {

    @Test
    fun isServerUpdateAvailableDetectsNewerVersionCode() {
        assertTrue(AndroidUpdatePolicy.isServerUpdateAvailable(currentVersionCode = 10L, serverVersionCode = 11L))
        assertFalse(AndroidUpdatePolicy.isServerUpdateAvailable(currentVersionCode = 11L, serverVersionCode = 11L))
        assertFalse(AndroidUpdatePolicy.isServerUpdateAvailable(currentVersionCode = 12L, serverVersionCode = 11L))
    }

    @Test
    fun releaseKeyCombinesVersionCodeAndVersionString() {
        assertEquals("12:v1.0.16", AndroidUpdatePolicy.releaseKey(12L, "v1.0.16"))
    }

    @Test
    fun resolveCompatibleApkPrefersArm64WhenAvailable() {
        val arm64Apk = ApkPackageInfo(available = true, abi = "arm64-v8a", url = "/apk/arm64.apk", size = 20_000_000L)
        val armv7Apk = ApkPackageInfo(available = true, abi = "armeabi-v7a", url = "/apk/armv7.apk", size = 18_000_000L)
        val release = ReleaseInfo(
            version = "v1.0.16",
            versionCode = 16L,
            tv = TvPackages(arm64V8a = arm64Apk, armeabiV7a = armv7Apk),
        )

        val resolved = AndroidUpdatePolicy.resolveCompatibleApk(release, arrayOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("arm64-v8a", resolved?.abi)
        assertEquals("/apk/arm64.apk", resolved?.url)
    }

    @Test
    fun resolveCompatibleApkFallsBackToArmV7() {
        val arm64Apk = ApkPackageInfo(available = true, abi = "arm64-v8a", url = "/apk/arm64.apk", size = 20_000_000L)
        val armv7Apk = ApkPackageInfo(available = true, abi = "armeabi-v7a", url = "/apk/armv7.apk", size = 18_000_000L)
        val release = ReleaseInfo(
            version = "v1.0.16",
            versionCode = 16L,
            tv = TvPackages(arm64V8a = arm64Apk, armeabiV7a = armv7Apk),
        )

        val resolved = AndroidUpdatePolicy.resolveCompatibleApk(release, arrayOf("armeabi-v7a"))
        assertEquals("armeabi-v7a", resolved?.abi)
    }

    @Test
    fun resolveCompatibleApkReturnsNullOnUnsupportedAbi() {
        val release = ReleaseInfo(
            version = "v1.0.16",
            versionCode = 16L,
            tv = TvPackages(
                arm64V8a = ApkPackageInfo(available = true, abi = "arm64-v8a", url = "/apk/arm64.apk"),
                armeabiV7a = ApkPackageInfo(available = true, abi = "armeabi-v7a", url = "/apk/armv7.apk"),
            ),
        )

        val resolved = AndroidUpdatePolicy.resolveCompatibleApk(release, arrayOf("x86_64", "x86"))
        assertNull(resolved)
    }

    @Test
    fun isHostActivityAliveChecksBothFinishingAndDestroyed() {
        assertTrue(AndroidUpdatePolicy.isHostActivityAlive(isFinishing = false, isDestroyed = false))
        assertFalse(AndroidUpdatePolicy.isHostActivityAlive(isFinishing = true, isDestroyed = false))
        assertFalse(AndroidUpdatePolicy.isHostActivityAlive(isFinishing = false, isDestroyed = true))
        assertFalse(AndroidUpdatePolicy.isHostActivityAlive(isFinishing = true, isDestroyed = true))
    }

    @Test
    fun resolveCompatibleApkFallsBackToArmV7WhenArm64UnavailableOn64BitDevice() {
        val arm64Apk = ApkPackageInfo(available = false, abi = "arm64-v8a", url = "")
        val armv7Apk = ApkPackageInfo(available = true, abi = "armeabi-v7a", url = "/apk/armv7.apk", size = 18_000_000L)
        val release = ReleaseInfo(
            version = "v1.0.16",
            versionCode = 16L,
            tv = TvPackages(arm64V8a = arm64Apk, armeabiV7a = armv7Apk),
        )

        val resolved = AndroidUpdatePolicy.resolveCompatibleApk(release, arrayOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("armeabi-v7a", resolved?.abi)
        assertEquals("/apk/armv7.apk", resolved?.url)
    }

    @Test
    fun resolveCompatibleApkMustNotFallBackToArmV7OnArm64OnlyDevice() {
        val arm64Apk = ApkPackageInfo(available = false, abi = "arm64-v8a", url = "")
        val armv7Apk = ApkPackageInfo(available = true, abi = "armeabi-v7a", url = "/apk/armv7.apk", size = 18_000_000L)
        val release = ReleaseInfo(
            version = "v1.0.16",
            versionCode = 16L,
            tv = TvPackages(arm64V8a = arm64Apk, armeabiV7a = armv7Apk),
        )

        val resolved = AndroidUpdatePolicy.resolveCompatibleApk(release, arrayOf("arm64-v8a"))
        assertNull(resolved)
    }
}
