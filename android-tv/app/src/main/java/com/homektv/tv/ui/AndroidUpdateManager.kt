package com.homektv.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.homektv.tv.BuildConfig
import com.homektv.tv.net.ApkPackageInfo
import com.homektv.tv.net.MediaApi
import com.homektv.tv.net.ReleaseInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

object AndroidUpdatePolicy {
    fun isServerUpdateAvailable(currentVersionCode: Long, serverVersionCode: Long): Boolean =
        serverVersionCode > currentVersionCode

    fun resolveCompatibleApk(release: ReleaseInfo, supportedAbis: Array<String>): ApkPackageInfo? {
        val arm64 = release.tv.arm64V8a.takeIf { it.available && it.url.isNotBlank() }
        val armv7 = release.tv.armeabiV7a.takeIf { it.available && it.url.isNotBlank() }

        val supportsArm64 = supportedAbis.contains("arm64-v8a")
        val supportsArmv7 = supportedAbis.contains("armeabi-v7a")

        return when {
            supportsArm64 && arm64 != null -> arm64
            supportsArmv7 && armv7 != null -> armv7
            else -> null
        }
    }

    fun releaseKey(versionCode: Long, version: String): String = "$versionCode:$version"

    fun isHostActivityAlive(isFinishing: Boolean, isDestroyed: Boolean): Boolean =
        !isFinishing && !isDestroyed
}

class AndroidUpdateManager(
    private val activity: AppCompatActivity,
    private val mediaApi: MediaApi,
    private val onToast: (String) -> Unit = { msg ->
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    },
) {
    private var releaseCheckInFlight = false
    private var checkedReleaseVersion: String? = null
    private var promptedReleaseVersion: String? = null
    private var pendingUpdateApk: File? = null

    private fun isActivityAlive(): Boolean =
        AndroidUpdatePolicy.isHostActivityAlive(activity.isFinishing, activity.isDestroyed)

    private val unknownSourcesLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = pendingUpdateApk ?: return@registerForActivityResult
        if (activity.packageManager.canRequestPackageInstalls()) {
            installDownloadedApk(apk)
        } else {
            onToast("未允许安装此来源的应用")
        }
    }

    fun checkForUpdate(scope: CoroutineScope) {
        if (releaseCheckInFlight || !isActivityAlive()) return
        releaseCheckInFlight = true
        scope.launch {
            val release = mediaApi.fetchReleaseInfo()
            releaseCheckInFlight = false
            if (!isActivityAlive()) return@launch
            if (release == null || release.version.isBlank() || release.versionCode <= 0) return@launch
            val releaseKey = AndroidUpdatePolicy.releaseKey(release.versionCode, release.version)
            if (checkedReleaseVersion == releaseKey) return@launch
            checkedReleaseVersion = releaseKey
            if (!AndroidUpdatePolicy.isServerUpdateAvailable(BuildConfig.VERSION_CODE.toLong(), release.versionCode)
                || promptedReleaseVersion == releaseKey) return@launch

            val apk = AndroidUpdatePolicy.resolveCompatibleApk(release, Build.SUPPORTED_ABIS)
            if (apk == null || !apk.available || apk.url.isBlank()) {
                if (isActivityAlive()) {
                    onToast("服务端版本为 ${release.version}，但没有适配本机架构的安装包")
                }
                return@launch
            }
            promptedReleaseVersion = releaseKey
            showUpdateDialog(release.version, release.versionCode, apk, scope)
        }
    }

    private fun showUpdateDialog(version: String, versionCode: Long, apk: ApkPackageInfo, scope: CoroutineScope) {
        if (!isActivityAlive()) return
        val size = if (apk.size > 0) " · %.1f MB".format(Locale.US, apk.size / 1024.0 / 1024.0) else ""
        AlertDialog.Builder(activity)
            .setTitle("发现新版本")
            .setMessage("当前版本 ${BuildConfig.VERSION_NAME}\n服务端版本 $version\n安装包 ${apk.abi}$size")
            .setNegativeButton("暂不更新", null)
            .setPositiveButton("去下载") { _, _ -> downloadAndInstallUpdate(version, versionCode, apk, scope) }
            .show()
    }

    private fun downloadAndInstallUpdate(version: String, versionCode: Long, apk: ApkPackageInfo, scope: CoroutineScope) {
        if (!isActivityAlive()) return
        val progress = AlertDialog.Builder(activity)
            .setTitle("正在下载 $version")
            .setMessage("安装包下载完成后将打开系统安装界面。")
            .setCancelable(false)
            .create()
        progress.show()
        scope.launch {
            val destination = File(activity.cacheDir, "updates/home-ktv-${apk.abi}.apk")
            val downloaded = mediaApi.downloadApk(apk.url, destination, apk.size)
            if (isActivityAlive()) {
                try {
                    progress.dismiss()
                } catch (_: Throwable) {
                }
            }
            if (!isActivityAlive()) return@launch
            if (!downloaded) {
                checkedReleaseVersion = null
                promptedReleaseVersion = null
                AlertDialog.Builder(activity)
                    .setTitle("安装包下载失败")
                    .setMessage("请检查服务端连接后重试。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重试") { _, _ -> downloadAndInstallUpdate(version, versionCode, apk, scope) }
                    .show()
                return@launch
            }
            val verified = ApkArchiveVerifier.verifyApk(activity, destination, versionCode)
            if (!verified) {
                destination.delete()
                checkedReleaseVersion = null
                promptedReleaseVersion = null
                onToast("安装包验证失败：文件损坏或签名不匹配")
                return@launch
            }
            pendingUpdateApk = destination
            requestInstallOrOpen(destination)
        }
    }

    private fun requestInstallOrOpen(apk: File) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            installDownloadedApk(apk)
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"),
        )
        runCatching { unknownSourcesLauncher.launch(intent) }
            .onFailure { onToast("当前设备无法打开未知来源安装设置") }
    }

    private fun installDownloadedApk(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { onToast("无法打开系统安装程序") }
    }
}
