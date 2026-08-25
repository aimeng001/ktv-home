package com.homektv.tv.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.KeyEvent
import android.widget.Toast
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.view.Gravity
import androidx.annotation.OptIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import com.homektv.tv.BuildConfig
import com.homektv.tv.R
import com.homektv.tv.databinding.ActivityMainBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.KtvSocket
import com.homektv.tv.net.MediaApi
import com.homektv.tv.net.ApkPackageInfo
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.StandbyContent
import com.homektv.tv.player.PlaybackEngine
import com.homektv.tv.player.EffectPlayer
import com.homektv.tv.player.LrcParser
import com.homektv.tv.player.LyricLine
import com.homektv.tv.player.MicrophoneMonitor
import kotlinx.coroutines.launch
import java.io.File

/**
 * TV 主界面。
 * - 未配置 NAS 地址 → 跳 SetupActivity（详设§12.1）
 * - 已配置 → 连 WS、渲染待机页 TV-01（简化版），并按快照驱动播放（P1.28）
 *
 * 播放驱动（P1.28）：以服务端快照为唯一事实源。
 *   - state=playing 且有 now_playing → 拉详情取文件源 → ExoPlayer 拉流播放，
 *     显示播放层、隐藏待机页；每 1s 上行 progress、播完上行 finished
 *   - state=paused → 暂停
 *   - state=idle / 无 now_playing → 停止播放，回待机页
 *
 * 完整播放页 UI（双行歌词/信息条/进度条 TV-02/03）、双音轨切换（P1.29）、
 * 遥控浮层（TV-04/05）在后续任务叠加。
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : AppCompatActivity(), KtvSocket.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private lateinit var mediaApi: MediaApi
    private var socket: KtvSocket? = null
    private var engine: PlaybackEngine? = null
    private var effectPlayer: EffectPlayer? = null
    private var effectOverlay: EffectOverlayView? = null
    private var microphoneMonitor: MicrophoneMonitor? = null
    private var microphoneActive = false
    private var releaseCheckInFlight = false
    private var checkedReleaseVersion: String? = null
    private var promptedReleaseVersion: String? = null
    private var pendingUpdateApk: File? = null
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val recordGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (recordGranted) startMicrophoneMonitor()
        else onToast("未授予麦克风权限")
    }
    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = pendingUpdateApk ?: return@registerForActivityResult
        if (packageManager.canRequestPackageInstalls()) installDownloadedApk(apk)
        else onToast("未允许安装此来源的应用")
    }

    /** 当前正在播放的 queueId，用于判断快照是否切了歌。 */
    private var currentQueueId: Long? = null
    private var currentFileId: Long? = null
    private var accompanimentTrackIndex: Int? = null
    private var audioTrackCount: Int = 1
    private var lyricLines: List<LyricLine> = emptyList()
    private var lastLyricIndex = -1
    private var lastProgressReportMs = Long.MIN_VALUE
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            if (::binding.isInitialized) binding.txtClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            clock.postDelayed(this, 30_000L)
        }
    }
    private val menuHide = Runnable { binding.remoteMenu.visibility = View.GONE }
    private val progressHide = Runnable { hidePlaybackProgress() }
    private var recommendations: List<com.homektv.tv.net.SongDto> = emptyList()
    private var recommendationOffset = 0
    private val recommendationCovers = mutableMapOf<Long, android.graphics.Bitmap?>()
    private var standbyCarouselEnabled = true
    private var antiBurnEnabled = true
    private var standbyIntervalMs = 8_000L
    private val standbyMotionAnimators = mutableListOf<ObjectAnimator>()
    private var currentPlaybackState = "idle"
    private var hasCurrentSong = false
    private val standbyTicker = object : Runnable {
        override fun run() {
            if (standbyCarouselEnabled && recommendations.isNotEmpty()) {
                renderRecommendationCards()
                recommendationOffset = (recommendationOffset + 1) % recommendations.size
            }
            binding.standbyPanel.postDelayed(this, standbyIntervalMs)
        }
    }
    private val burnInTicker = object : Runnable {
        override fun run() {
            if (antiBurnEnabled) {
                val step = if (binding.standbyPanel.translationX >= 1f) -1f else 1f
                binding.standbyPanel.translationX = step
                binding.standbyPanel.translationY = -step
            } else {
                binding.standbyPanel.translationX = 0f
                binding.standbyPanel.translationY = 0f
            }
            binding.standbyPanel.postDelayed(this, 60_000L)
        }
    }
    private val standbySettingsTicker = object : Runnable {
        override fun run() {
            lifecycleScope.launch { applyStandbyContent(mediaApi.fetchStandbyContent()) }
            binding.standbyPanel.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        val audioPreview = intent.action == "com.homektv.tv.action.AUDIO_PREVIEW" ||
            intent.getBooleanExtra("audio_preview", false)

        if (!config.isConfigured && !audioPreview) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // 永不休眠（详设§12.2：点歌机常亮）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        clock.post(clockTick)

        binding.txtAddress.text = config.h5Url()

        mediaApi = MediaApi(config)
        loadQr()
        loadStandbyContent()
        startStandbyMotion()
        binding.standbyPanel.post(standbyTicker)
        binding.standbyPanel.postDelayed(burnInTicker, 60_000L)
        binding.standbyPanel.post(standbySettingsTicker)
        engine = PlaybackEngine(
            context = this,
            onProgress = { pos ->
                // UI 以高频本地时钟平滑刷新，服务端进度仍保持 1s 上报频率。
                if (lastProgressReportMs == Long.MIN_VALUE || pos - lastProgressReportMs >= 1_000L) {
                    lastProgressReportMs = pos
                    socket?.sendProgress(pos, currentQueueId)
                }
                runOnUiThread { updateProgress(pos) }
            },
            onFinished = { socket?.sendFinished(currentQueueId) },
            onError = { onPlayError() },
        ).also {
            it.attach(binding.playerView)
            // 遥控音量键在此 ROM 上直达系统媒体会话：监听系统音量变化上行同步服务端
            it.onExternalVolumeChange = { percent ->
                if (percent != currentVolume) sendControl("set_volume", "{\"volume\":$percent}")
            }
        }
        effectPlayer = EffectPlayer()
        microphoneMonitor = MicrophoneMonitor(this) { state ->
            runOnUiThread {
                microphoneActive = state.active
                updateMicrophoneButton()
                state.message?.let(::onToast)
                if (state.active) onToast("麦克风已接入：${state.deviceName}")
            }
        }
        effectOverlay = EffectOverlayView(this).also { overlay ->
            overlay.visibility = View.GONE
            binding.root.addView(
                overlay,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }

        setupRemoteMenu()
        if (audioPreview) {
            renderAudioPreview()
        } else {
            socket = KtvSocket(config, this).also { it.connect() }
            if (config.microphoneMonitorEnabled) ensureMicrophonePermissionsAndStart()
        }
    }

    private fun renderAudioPreview() {
        showPlayer(audioMode = true)
        binding.imgAudioCover.setImageDrawable(null)
        binding.txtAudioFallback.text = "晴天"
        binding.txtAudioTitle.text = "晴天"
        binding.txtAudioArtist.text = "周杰伦"
        binding.txtAudioNext.text = "接下来  海阔天空 · Beyond"
        binding.txtAudioLyricCurrent.setLine(LyricLine(0L, "童年的荡秋千 随记忆一直晃到现在"), 10_000L)
        binding.txtAudioLyricCurrent.updatePlayback(4_200L, false)
        binding.txtAudioLyricNext.text = "吹着前奏望着天空"
        binding.audioProgress.progress = 420
        binding.txtAudioElapsed.text = "01:42"
        binding.txtAudioDuration.text = "04:03"
    }

    override fun onDestroy() {
        standbyMotionAnimators.forEach(ObjectAnimator::cancel)
        standbyMotionAnimators.clear()
        socket?.close()
        clock.removeCallbacks(clockTick)
        clock.removeCallbacks(progressHide)
        binding.standbyPanel.removeCallbacks(standbyTicker)
        binding.standbyPanel.removeCallbacks(burnInTicker)
        binding.standbyPanel.removeCallbacks(standbySettingsTicker)
        socket = null
        engine?.detach(binding.playerView)
        engine?.release()
        engine = null
        effectPlayer?.release()
        effectPlayer = null
        effectOverlay?.clear()
        effectOverlay = null
        microphoneMonitor?.release()
        microphoneMonitor = null
        super.onDestroy()
    }

    /** 顶层 BACK 双击退出：此 ROM 会在 Activity 退到后台 1s 内强杀进程，单击防误触。 */
    private var lastBackAt = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && binding.playerView.visibility == View.VISIBLE) {
            showPlaybackProgress()
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            hideVocalPanel()
            binding.remoteMenu.visibility = if (binding.remoteMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (binding.remoteMenu.visibility == View.VISIBLE) binding.remotePlay.requestFocus()
            resetMenuTimer()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && binding.remoteMenu.visibility == View.VISIBLE) {
            binding.remoteMenu.visibility = View.GONE
            return true
        }
        if (binding.remoteMenu.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) resetMenuTimer()
        if (binding.queueOverlay.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            binding.queueOverlay.visibility = View.GONE
            return true
        }
        // 原/伴唱选择栏：BACK 收起；可见期间方向键/确认键交给焦点系统（移动选择、点击生效）
        if (binding.vocalPanel.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideVocalPanel()
                return true
            }
            resetVocalTimer()
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    changeVolume(10)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    changeVolume(-10)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> {
                    sendControl("mute", "{\"muted\":${!currentMuted}}")
                    return true
                }
            }
            if (binding.remoteMenu.visibility != View.VISIBLE && binding.queueOverlay.visibility != View.VISIBLE) {
                when (event.keyCode) {
                    // 确认键：屏幕下方弹出原唱/伴唱选择栏（参考主流 KTV 交互）
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        showVocalPanel()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        togglePlayback()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        sendControl("next")
                        return true
                    }
                    // 左键直接切歌（按用户习惯，不再呼出队列；队列可从遥控菜单进入）
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        sendControl("next")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        changeVolume(5)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        changeVolume(-5)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- 音量 OSD ----

    private val volumeHide = Runnable { binding.volumeOsd.visibility = View.GONE }

    /** 任何来源的音量变化都调用：顶部显示 2.5s 当前音量与满量程进度。 */
    private fun showVolumeOsd(volume: Int, muted: Boolean) {
        binding.txtVolume.text = if (muted) "已静音" else "音量 $volume"
        binding.volumeBar.progress = if (muted) 0 else volume
        binding.volumeOsd.visibility = View.VISIBLE
        binding.volumeOsd.removeCallbacks(volumeHide)
        binding.volumeOsd.postDelayed(volumeHide, 2_500L)
    }

    // ---- 原唱/伴唱选择栏 ----

    private val vocalHide = Runnable { binding.vocalPanel.visibility = View.GONE }

    private fun showVocalPanel() {
        updateVocalPanelSelection()
        binding.vocalPanel.visibility = View.VISIBLE
        (if (currentVocalMode == "original") binding.btnVocalOriginal else binding.btnVocalAccompaniment).requestFocus()
        resetVocalTimer()
    }

    private fun hideVocalPanel() {
        binding.vocalPanel.visibility = View.GONE
    }

    private fun resetVocalTimer() {
        binding.vocalPanel.removeCallbacks(vocalHide)
        binding.vocalPanel.postDelayed(vocalHide, 6_000L)
    }

    /** 当前生效模式金色高亮，另一个白色。 */
    private fun updateVocalPanelSelection() {
        val gold = resources.getColor(R.color.gold, null)
        val white = resources.getColor(android.R.color.white, null)
        val original = currentVocalMode == "original"
        binding.btnVocalOriginal.setTextColor(if (original) gold else white)
        binding.btnVocalAccompaniment.setTextColor(if (original) white else gold)
    }

    private fun setupRemoteMenu() {
        binding.remotePlay.setOnClickListener { togglePlayback() }
        binding.remoteNext.setOnClickListener { sendControl("next") }
        binding.remoteRestart.setOnClickListener { sendControl("restart") }
        binding.remoteVocal.setOnClickListener { toggleVocal() }
        binding.remoteVolUp.setOnClickListener { changeVolume(10) }
        binding.remoteVolDown.setOnClickListener { changeVolume(-10) }
        binding.remoteMute.setOnClickListener { sendControl("mute", "{\"muted\":${!currentMuted}}") }
        binding.remoteQueue.setOnClickListener { showQueueOverlay() }
        binding.remoteMicrophone.setOnClickListener { toggleMicrophoneMonitor() }
        updateMicrophoneButton()
        binding.queueClose.setOnClickListener { binding.queueOverlay.visibility = View.GONE }
        binding.btnVocalOriginal.setOnClickListener {
            sendControl("set_vocal", "{\"mode\":\"original\"}")
            hideVocalPanel()
        }
        binding.btnVocalAccompaniment.setOnClickListener {
            sendControl("set_vocal", "{\"mode\":\"accompaniment\"}")
            hideVocalPanel()
        }
        binding.queueNow.isFocusable = true
        binding.queueNow.setOnLongClickListener {
            if (hasCurrentSong) confirmControl("切掉当前歌曲", "确定切换到下一首吗？", "next")
            true
        }
    }

    private fun resetMenuTimer() {
        binding.remoteMenu.removeCallbacks(menuHide)
        binding.remoteMenu.postDelayed(menuHide, 10_000L)
    }

    private fun toggleMicrophoneMonitor() {
        if (microphoneActive) {
            config.microphoneMonitorEnabled = false
            microphoneMonitor?.stop()
            microphoneActive = false
            updateMicrophoneButton()
            onToast("麦克风监听已关闭")
        } else {
            config.microphoneMonitorEnabled = true
            ensureMicrophonePermissionsAndStart()
        }
    }

    private fun ensureMicrophonePermissionsAndStart() {
        val permissions = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            val bluetoothInputPresent = microphoneMonitor?.externalInputs()
                ?.any(com.homektv.tv.player.MicrophoneInputSelector::isBluetoothInput) == true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bluetoothInputPresent &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (permissions.isEmpty()) startMicrophoneMonitor()
        else microphonePermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startMicrophoneMonitor() {
        if (microphoneMonitor?.start() != true) updateMicrophoneButton()
    }

    private fun updateMicrophoneButton() {
        if (!::binding.isInitialized) return
        binding.remoteMicrophone.text = if (microphoneActive) "麦克风：开" else "麦克风：关"
    }

    private fun applyVideoScaleMode(mode: String) {
        binding.playerView.resizeMode = when (mode) {
            "fit" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    private var currentVolume = 60
    private var currentMuted = false
    private var currentVocalMode = "accompaniment"
    private var currentAudioLayout = AudioLayout.normalStereo()

    private fun togglePlayback() {
        sendControl(if (currentPlaybackState == "playing") "pause" else "play")
    }

    /** 原唱 ↔ 伴唱切换（遥控菜单按钮与方向下键共用）。 */
    private fun toggleVocal() {
        sendControl("set_vocal", "{\"mode\":\"${if (currentVocalMode == "original") "accompaniment" else "original"}\"}")
    }

    private fun changeVolume(delta: Int) {
        val volume = (currentVolume + delta).coerceIn(0, 100)
        sendControl("set_volume", "{\"volume\":$volume}")
    }

    private fun showQueueOverlay() {
        binding.remoteMenu.visibility = View.GONE
        binding.queueOverlay.visibility = View.VISIBLE
        val target = binding.queueList.getChildAt(0) ?: binding.queueClose
        target.requestFocus()
    }

    private fun confirmControl(title: String, message: String, action: String, params: String = "{}") {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> sendControl(action, params) }
            .show()
    }

    private fun sendControl(action: String, params: String = "{}") {
        showPlaybackProgress()
        lifecycleScope.launch { if (!mediaApi.control(action, params)) onToast("操作失败") }
    }

    // ---- KtvSocket.Listener ----

    override fun onConnectionChanged(connected: Boolean) {
        binding.txtStatus.setText(
            if (connected) R.string.status_connected else R.string.status_connecting
        )
        if (connected) checkForTvUpdate()
    }

    private fun checkForTvUpdate() {
        if (releaseCheckInFlight) return
        releaseCheckInFlight = true
        lifecycleScope.launch {
            val release = mediaApi.fetchReleaseInfo()
            releaseCheckInFlight = false
            if (release == null || release.version.isBlank() || release.versionCode <= 0) return@launch
            val releaseKey = "${release.versionCode}:${release.version}"
            if (checkedReleaseVersion == releaseKey) return@launch
            checkedReleaseVersion = releaseKey
            if (release.versionCode == BuildConfig.VERSION_CODE.toLong() || promptedReleaseVersion == releaseKey) return@launch

            val apk = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a") -> release.tv.arm64V8a
                Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> release.tv.armeabiV7a
                else -> null
            }
            if (apk == null || !apk.available || apk.url.isBlank()) {
                onToast("服务端版本为 ${release.version}，但没有适配本机架构的安装包")
                return@launch
            }
            promptedReleaseVersion = releaseKey
            showUpdateDialog(release.version, apk)
        }
    }

    private fun showUpdateDialog(version: String, apk: ApkPackageInfo) {
        val size = if (apk.size > 0) " · %.1f MB".format(Locale.US, apk.size / 1024.0 / 1024.0) else ""
        AlertDialog.Builder(this)
            .setTitle("发现 Android TV 新版本")
            .setMessage("当前版本 ${BuildConfig.VERSION_NAME}\n服务端版本 $version\n安装包 ${apk.abi}$size")
            .setNegativeButton("暂不更新", null)
            .setPositiveButton("去下载") { _, _ -> downloadAndInstallUpdate(version, apk) }
            .show()
    }

    private fun downloadAndInstallUpdate(version: String, apk: ApkPackageInfo) {
        val progress = AlertDialog.Builder(this)
            .setTitle("正在下载 $version")
            .setMessage("安装包下载完成后将打开系统安装界面。")
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val destination = File(cacheDir, "updates/home-ktv-tv-${apk.abi}.apk")
            val downloaded = mediaApi.downloadApk(apk.url, destination, apk.size)
            progress.dismiss()
            if (!downloaded) {
                checkedReleaseVersion = null
                promptedReleaseVersion = null
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("安装包下载失败")
                    .setMessage("请检查服务端连接后重试。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重试") { _, _ -> downloadAndInstallUpdate(version, apk) }
                    .show()
                return@launch
            }
            pendingUpdateApk = destination
            requestInstallOrOpen(destination)
        }
    }

    private fun requestInstallOrOpen(apk: File) {
        if (packageManager.canRequestPackageInstalls()) {
            installDownloadedApk(apk)
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        )
        runCatching { unknownSourcesLauncher.launch(intent) }
            .onFailure { onToast("当前电视无法打开未知来源安装设置") }
    }

    private fun installDownloadedApk(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { onToast("无法打开系统安装程序") }
    }

    private var snapshotReceived = false

    override fun onSnapshot(event: String, snapshot: QueueSnapshot) {
        // 音量/静音变化（遥控音量键、H5、遥控菜单任何来源）→ 顶部 OSD；首个快照不弹
        if (snapshotReceived && (snapshot.volume != currentVolume || snapshot.muted != currentMuted)) {
            showVolumeOsd(snapshot.volume, snapshot.muted)
        }
        snapshotReceived = true
        currentVolume = snapshot.volume
        currentMuted = snapshot.muted
        currentVocalMode = snapshot.vocalMode
        currentAudioLayout = snapshot.audioLayout
        if (binding.vocalPanel.visibility == View.VISIBLE) updateVocalPanelSelection()
        currentPlaybackState = snapshot.state
        val lyricsPlaying = snapshot.state == "playing"
        binding.txtLyricPrevious.updatePlayback(engine?.currentPositionMs ?: 0L, lyricsPlaying)
        binding.txtAudioLyricCurrent.updatePlayback(engine?.currentPositionMs ?: 0L, lyricsPlaying)
        hasCurrentSong = snapshot.playing != null
        renderQueue(snapshot)
        binding.txtPhones.text =
            getString(R.string.status_phones, snapshot.connectedPhones.toInt())
        applyPlayback(snapshot)
        if (event == PLAYBACK_RESTARTED_EVENT) {
            engine?.restart()
            lastLyricIndex = -1
            updateProgress(0L)
        }
        if (event == PLAYBACK_SEEKED_EVENT) {
            engine?.seekTo(snapshot.positionMs)
            lastLyricIndex = -1
            updateProgress(snapshot.positionMs)
        }
        if (event == VOCAL_CHANGED_EVENT) refreshVocalTrackMapping(snapshot)
    }

    private fun renderQueue(snapshot: QueueSnapshot) {
        val now = snapshot.playing?.song
        binding.queueNow.text = if (now == null) "当前演唱：暂无" else "正在演唱  ${now.title} · ${now.artist}"
        binding.queueList.removeAllViews()
        snapshot.list.forEachIndexed { index, item ->
            val song = item.song ?: return@forEachIndexed
            val row = TextView(this).apply {
                text = "%02d    %s · %s    %s".format(index + 1, song.title, song.artist, item.orderedByNick ?: "")
                textSize = 20f
                setTextColor(getColor(R.color.dim))
                setPadding(18, 20, 18, 20)
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    item.queueId?.let { sendControl("top", "{\"queue_id\":$it}") }
                }
                setOnLongClickListener {
                    item.queueId?.let {
                        confirmControl(
                            "删除待播歌曲",
                            "确定从队列删除《${song.title}》吗？",
                            "cancel",
                            "{\"queue_id\":$it}",
                        )
                    }
                    true
                }
            }
            binding.queueList.addView(row)
        }
        if (binding.queueOverlay.visibility == View.VISIBLE && binding.queueOverlay.findFocus() == null) {
            (binding.queueList.getChildAt(0) ?: binding.queueClose).requestFocus()
        }
    }

    override fun onToast(text: String) {
        if (text.isNotBlank()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onEffect(effectId: String) {
        effectPlayer?.play(effectId, currentVolume, currentMuted)
        effectOverlay?.play(effectId)
    }

    // ---- 播放驱动（P1.28） ----

    private fun applyPlayback(snapshot: QueueSnapshot) {
        val playing = snapshot.playing
        val songId = playing?.song?.id

        // idle 或无当前曲目：停止、回待机页
        if (snapshot.state == "idle" || playing == null || songId == null) {
            currentQueueId = null
            currentAudioLayout = AudioLayout.normalStereo()
            engine?.stop()
            binding.txtLyricPrevious.stopAnimation()
            binding.txtAudioLyricCurrent.stopAnimation()
            showStandby()
            return
        }

        updatePlayerInfo(snapshot)

        val eng = engine ?: return
        val volume = snapshot.volume
        val muted = snapshot.muted

        // 同一首：只处理播放/暂停 + 音量，不重新装载
        if (playing.queueId == currentQueueId) {
            eng.applyVolume(volume, muted)
            eng.setVocalMode(snapshot.vocalMode, accompanimentTrackIndex, audioTrackCount, currentAudioLayout)
            if (snapshot.state == "paused") eng.pause() else eng.resume()
            return
        }

        // 换歌：拉详情取文件源 → 播放
        currentQueueId = playing.queueId
        val targetQueueId = playing.queueId
        val audioMode = playing.song?.mediaType.equals("AUDIO", ignoreCase = true)
        lyricLines = emptyList()
        lastLyricIndex = -1
        binding.txtLyricPrevious.animate().cancel()
        binding.txtLyricCurrent.animate().cancel()
        binding.txtLyricPrevious.alpha = 1f
        binding.txtLyricCurrent.alpha = 1f
        binding.txtLyricPrevious.stopAnimation()
        binding.txtLyricCurrent.text = ""
        binding.txtAudioLyricCurrent.stopAnimation()
        binding.txtAudioLyricNext.text = ""
        showPlayer(audioMode)
        if (audioMode) {
            val song = playing.song
            binding.imgAudioCover.setImageDrawable(null)
            binding.txtAudioFallback.text = song?.title.orEmpty().take(2).ifBlank { "KTV" }
            binding.txtAudioTitle.text = song?.title.orEmpty()
            binding.txtAudioArtist.text = song?.artist.orEmpty()
            binding.txtAudioLyricCurrent.setLine(null, 0L)
            binding.txtAudioLyricNext.text = song?.title.orEmpty()
        }
        lifecycleScope.launch {
            val file = mediaApi.bestFileSource(songId)
            if (currentQueueId != targetQueueId) return@launch
            if (file == null) {
                onPlayError()
                return@launch
            }
            accompanimentTrackIndex = file.audioLayout.accompanimentTrackIndex ?: file.vocalTrackIndex
            audioTrackCount = file.audioTracks
            currentAudioLayout = file.audioLayout
            currentFileId = file.id
            lyricLines = mediaApi.fetchLyric(songId)?.let(LrcParser::parse).orEmpty()
            if (currentQueueId != targetQueueId) return@launch
            if (audioMode) {
                mediaApi.fetchCover(songId)?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { binding.imgAudioCover.setImageBitmap(it) }
                }
                if (lyricLines.isNotEmpty()) binding.txtAudioLyricNext.text = lyricLines.first().text
            }
            eng.setVocalMode(snapshot.vocalMode, accompanimentTrackIndex, audioTrackCount, currentAudioLayout)
            eng.applyVolume(volume, muted)
            eng.play(file.id, mediaApi.streamUrl(file.id))
            if (snapshot.state == "paused") eng.pause()
        }
    }

    private fun refreshVocalTrackMapping(snapshot: QueueSnapshot) {
        val playing = snapshot.playing ?: return
        val songId = playing.song?.id ?: return
        val targetQueueId = playing.queueId
        lifecycleScope.launch {
            val file = mediaApi.bestFileSource(songId) ?: return@launch
            if (currentQueueId != targetQueueId || currentFileId != file.id) return@launch
            accompanimentTrackIndex = file.audioLayout.accompanimentTrackIndex ?: file.vocalTrackIndex
            audioTrackCount = file.audioTracks
            currentAudioLayout = file.audioLayout
            engine?.setVocalMode(snapshot.vocalMode, accompanimentTrackIndex, audioTrackCount, currentAudioLayout)
        }
    }

    private fun startStandbyMotion() {
        binding.qrPanel.post {
            ObjectAnimator.ofFloat(binding.qrPanel, View.SCALE_X, 1f, 1.012f).apply {
                duration = 1_800L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                standbyMotionAnimators += this
                start()
            }
            ObjectAnimator.ofFloat(binding.qrPanel, View.SCALE_Y, 1f, 1.012f).apply {
                duration = 1_800L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                standbyMotionAnimators += this
                start()
            }
        }
    }

    // ---- 待机页二维码（P1.30） ----

    /** 异步拉 /api/qr 加载进待机页占位框；失败保留占位文字（用户仍可读明文地址）。 */
    private fun loadQr() {
        lifecycleScope.launch {
            val bytes = mediaApi.fetchQr(QR_SIZE_PX)
            val bmp = bytes?.let {
                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
            }
            if (bmp != null) {
                binding.imgQr.setImageBitmap(bmp)
                binding.imgMiniQr.setImageBitmap(bmp)
                binding.imgAudioMiniQr.setImageBitmap(bmp)
                binding.imgQr.visibility = View.VISIBLE
                binding.txtQrPlaceholder.visibility = View.GONE
            }
        }
    }

    private fun loadStandbyContent() {
        lifecycleScope.launch {
            val content = mediaApi.fetchStandbyContent()
            applyStandbyContent(content)
            val libraryCount = mediaApi.fetchLibraryCount()
            binding.txtLibraryStat.text = "曲库 ${libraryCount ?: recommendations.size} 首"
            binding.recommendationRow.visibility = if (recommendations.isEmpty()) View.GONE else View.VISIBLE
            binding.txtRecommendationsEmpty.visibility = if (recommendations.isEmpty()) View.VISIBLE else View.GONE
            if (recommendations.isNotEmpty()) renderRecommendationCards()
        }
    }

    private fun applyStandbyContent(content: StandbyContent) {
        applyVideoScaleMode(content.videoScaleMode)
        standbyCarouselEnabled = content.carouselEnabled
        antiBurnEnabled = content.antiBurn
        standbyIntervalMs = content.intervalSeconds.coerceIn(3, 60) * 1_000L
        binding.txtStandbyWelcome.text = content.welcomeText
        binding.txtStandbySubtitle.text = content.subtitle
        recommendations = content.songs.sortedByDescending { it.coverUrl != null }
        binding.recommendationRow.visibility = if (recommendations.isEmpty()) View.GONE else View.VISIBLE
        binding.txtRecommendationsEmpty.visibility = if (recommendations.isEmpty()) View.VISIBLE else View.GONE
        if (recommendations.isNotEmpty()) renderRecommendationCards()
        if (content.logoUrl == null) {
            binding.imgStandbyLogo.setImageResource(R.drawable.home_ktv_logo)
            binding.imgStandbyLogo.visibility = View.VISIBLE
            binding.txtBrandName.visibility = View.VISIBLE
        } else {
            lifecycleScope.launch {
                val bitmap = mediaApi.fetchUrl(content.logoUrl)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap != null) {
                    binding.imgStandbyLogo.setImageBitmap(bitmap)
                    binding.imgStandbyLogo.visibility = View.VISIBLE
                    binding.txtBrandName.visibility = View.GONE
                }
            }
        }
        if (!antiBurnEnabled) {
            binding.standbyPanel.translationX = 0f
            binding.standbyPanel.translationY = 0f
        }
    }

    private fun renderRecommendationCards() {
        val visible = (0 until minOf(4, recommendations.size))
            .map { recommendations[(recommendationOffset + it) % recommendations.size] }
        binding.recommendationRow.removeAllViews()
        visible.forEach { song ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(4), dp(8), dp(4))
                setBackgroundResource(R.drawable.recommendation_card)
            }
            val params = LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(8) }
            binding.recommendationRow.addView(card, params)

            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(getColor(R.color.panel))
                setImageResource(R.drawable.home_ktv_logo)
                recommendationCovers[song.id]?.let(::setImageBitmap)
            }
            card.addView(cover, LinearLayout.LayoutParams(dp(50), dp(50)))

            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), 0, 0, 0)
            }
            card.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            labels.addView(TextView(this).apply {
                text = song.title
                setTextColor(getColor(android.R.color.white))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            labels.addView(TextView(this).apply {
                text = song.artist
                setTextColor(getColor(R.color.dim))
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            if (song.coverUrl != null && !recommendationCovers.containsKey(song.id)) {
                lifecycleScope.launch {
                    val bitmap = mediaApi.fetchCover(song.id)?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)
                    }
                    recommendationCovers[song.id] = bitmap
                    if (bitmap != null && song in visible) cover.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showPlayer(audioMode: Boolean = false) {
        binding.playerView.visibility = View.VISIBLE
        binding.standbyPanel.visibility = View.GONE
        binding.ktvOverlay.visibility = if (audioMode) View.GONE else View.VISIBLE
        binding.audioOverlay.visibility = if (audioMode) View.VISIBLE else View.GONE
        showPlaybackProgress()
    }

    private fun showStandby() {
        binding.playerView.visibility = View.GONE
        binding.standbyPanel.visibility = View.VISIBLE
        binding.ktvOverlay.visibility = View.GONE
        binding.audioOverlay.visibility = View.GONE
        hidePlaybackProgress()
    }

    private fun showPlaybackProgress() {
        if (!::binding.isInitialized || binding.playerView.visibility != View.VISIBLE) return
        binding.playerInfoPanel.visibility = View.VISIBLE
        binding.playProgress.visibility = View.VISIBLE
        binding.audioProgress.visibility = View.VISIBLE
        binding.txtElapsed.visibility = View.VISIBLE
        binding.txtVocalMode.visibility = View.VISIBLE
        binding.txtDuration.visibility = View.VISIBLE
        if (lyricLines.isEmpty()) {
            binding.txtLyricPrevious.visibility = View.VISIBLE
            binding.txtLyricCurrent.visibility = View.VISIBLE
        }
        clock.removeCallbacks(progressHide)
        clock.postDelayed(progressHide, PROGRESS_HIDE_DELAY_MS)
    }

    private fun hidePlaybackProgress() {
        if (!::binding.isInitialized) return
        binding.playerInfoPanel.visibility = View.GONE
        binding.playProgress.visibility = View.GONE
        val audioMode = binding.audioOverlay.visibility == View.VISIBLE
        binding.audioProgress.visibility = if (audioMode) View.VISIBLE else View.GONE
        binding.txtAudioElapsed.visibility = if (audioMode) View.VISIBLE else View.GONE
        binding.txtAudioDuration.visibility = if (audioMode) View.VISIBLE else View.GONE
        binding.txtElapsed.visibility = View.GONE
        binding.txtVocalMode.visibility = View.GONE
        binding.txtDuration.visibility = View.GONE
        if (lyricLines.isEmpty()) {
            binding.txtLyricPrevious.visibility = View.GONE
            binding.txtLyricCurrent.visibility = View.GONE
        }
    }

    private fun updatePlayerInfo(snapshot: QueueSnapshot) {
        val current = snapshot.playing ?: return
        val song = current.song ?: return
        binding.txtMediaBadge.text = if (song.mediaType == "KTV_VIDEO") "KTV版" else "MV"
        binding.txtPlayerTitle.text = song.title
        binding.txtPlayerArtist.text = song.artist
        binding.txtOrderedBy.text = current.orderedByNick?.let { "$it 点" } ?: ""
        val next = snapshot.list.firstOrNull { it.status == "waiting" }
        binding.nextPanel.visibility = if (next?.song != null) View.VISIBLE else View.GONE
        binding.txtNextSong.text = next?.song?.let { "${it.title} · ${next.orderedByNick ?: ""}" } ?: ""
        binding.txtAudioNext.text = next?.song?.let { "接下来  ${it.title} · ${it.artist}" } ?: ""
        // lyric timeline is delivered separately in the next lyric task; use the title as a temporary fallback.
        if (lyricLines.isEmpty()) {
            binding.txtLyricCurrent.text = song.title
            binding.txtLyricPrevious.setLine(null, 0L)
        }
        binding.txtVocalMode.text = if (song.hasVocalTrack) {
            if (snapshot.vocalMode == "original") "原唱中" else "伴唱中"
        } else ""
        binding.txtDuration.text = formatMs(song.durationMs.toLong())
    }

    private fun updateProgress(positionMs: Long) {
        val duration = engine?.durationMs ?: 0L
        binding.playProgress.progress = if (duration > 0) ((positionMs * 1000) / duration).toInt().coerceIn(0, 1000) else 0
        binding.audioProgress.progress = binding.playProgress.progress
        binding.txtAudioElapsed.text = formatMs(positionMs)
        binding.txtAudioDuration.text = formatMs(duration)
        binding.txtElapsed.text = formatMs(positionMs)
        if (lyricLines.isNotEmpty()) {
            binding.txtLyricPrevious.visibility = View.VISIBLE
            binding.txtLyricCurrent.visibility = View.VISIBLE
            val index = lyricLines.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
            if (index != lastLyricIndex) {
                lastLyricIndex = index
                binding.txtLyricPrevious.animate().cancel()
                binding.txtLyricCurrent.animate().cancel()
                binding.txtLyricPrevious.alpha = 0.55f
                binding.txtLyricCurrent.alpha = 0.35f
                binding.txtLyricPrevious.animate().alpha(1f).setDuration(180L).start()
                binding.txtLyricCurrent.animate().alpha(1f).setDuration(240L).start()
            }
            // 常规点歌机布局：当前演唱行在上，下一行预告在下。
            val lineEndMs = lyricLines.getOrNull(index + 1)?.startMs
                ?: duration.takeIf { it > lyricLines[index].startMs }
                ?: lyricLines[index].startMs + 5_000L
            binding.txtLyricPrevious.setLine(lyricLines[index], lineEndMs)
            binding.txtLyricPrevious.updatePlayback(positionMs, currentPlaybackState == "playing")
            binding.txtLyricCurrent.text = lyricLines.getOrNull(index + 1)?.text.orEmpty()
            if (binding.audioOverlay.visibility == View.VISIBLE) {
                binding.txtAudioLyricCurrent.setLine(lyricLines[index], lineEndMs)
                binding.txtAudioLyricCurrent.updatePlayback(positionMs, currentPlaybackState == "playing")
                binding.txtAudioLyricNext.text = lyricLines.getOrNull(index + 1)?.text.orEmpty()
            }
        }
    }

    private fun formatMs(ms: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    /** 播放失败：上报文件源，服务端标记失效并推进队列。 */
    private fun onPlayError() {
        Toast.makeText(this, R.string.play_error, Toast.LENGTH_SHORT).show()
        socket?.sendPlayError("media playback failed", currentFileId, currentQueueId)
    }

    companion object {
        /** 二维码请求边长（服务端在 [120,1080] 内钳制）；待机占位框 220dp，取 540 兼顾高密度电视清晰度。 */
        private const val QR_SIZE_PX = 540
        private const val PROGRESS_HIDE_DELAY_MS = 5_000L
        private const val VOCAL_CHANGED_EVENT = "vocal_changed"
        private const val PLAYBACK_RESTARTED_EVENT = "playback_restarted"
        private const val PLAYBACK_SEEKED_EVENT = "playback_seeked"
    }
}
