package com.homektv.tv.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
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
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import com.homektv.tv.BuildConfig
import com.homektv.tv.controller.ControllerViewModel
import com.homektv.tv.controller.ControllerViewModelFactory
import com.homektv.tv.session.DeviceMode
import com.homektv.tv.session.DeviceModeCapabilities
import com.homektv.tv.session.DeviceModeMigrationPolicy
import com.homektv.tv.session.DeviceSessionChangePolicy
import com.homektv.tv.session.DeviceSessionFingerprint
import com.homektv.tv.ui.controller.ControllerFragment
import com.homektv.tv.R
import com.homektv.tv.databinding.ActivityMainBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.KtvSocket
import com.homektv.tv.net.MediaApi
import com.homektv.tv.net.ApkPackageInfo
import com.homektv.tv.net.PlaybackErrorContext
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SnapshotRevisionPolicy
import com.homektv.tv.net.StandbyContent
import com.homektv.tv.player.PlaybackEngine
import com.homektv.tv.player.EffectPlayer
import com.homektv.tv.player.LrcParser
import com.homektv.tv.player.LyricLine
import com.homektv.tv.player.MicrophoneMonitor
import com.homektv.tv.player.PlaybackCoordinator
import com.homektv.tv.player.PlaybackSource
import com.homektv.tv.player.PlaybackReplacementRequest
import com.homektv.tv.player.PlaybackReplacementToken
import com.homektv.tv.player.PlaybackSourceFailurePolicy
import com.homektv.tv.player.PlaybackLoadGate
import com.homektv.tv.player.PlaybackLoadTicket
import com.homektv.tv.player.DesiredPlaybackState
import com.homektv.tv.player.PlaybackLoadProjection
import com.homektv.tv.player.PlaybackSeekGate
import com.homektv.tv.player.supportsVocalSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var presentationController: KtvPresentationController? = null
    private var externalDisplayActive = false
    /** The Presentation surface currently bound to the single playback engine. */
    private var attachedExternalPlayerView: androidx.media3.ui.PlayerView? = null
    private var microphoneActive = false
    private lateinit var updateManager: AndroidUpdateManager
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val recordGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (recordGranted) startMicrophoneMonitor()
        else onToast("未授予麦克风权限")
    }

    /** 当前请求中的 queueId，用于判断快照是否切了歌；播放器回调使用其已加载身份。 */
    private var currentQueueId: Long? = null
    /** 只有 eng.play 已提交后才设置，避免加载中的新快照操作旧媒体。 */
    private var loadedQueueId: Long? = null
    private var currentFileId: Long? = null
    private val playbackLoadGate = PlaybackLoadGate()
    private var playbackLoadJob: Job? = null
    private var playbackReplacementJob: Job? = null
    private var activePlaybackLoadTicket: PlaybackLoadTicket? = null
    private val desiredPlaybackState = DesiredPlaybackState()
    private val playbackLoadProjection = PlaybackLoadProjection(desiredPlaybackState)
    private val playbackSeekGate = PlaybackSeekGate()
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
    private val recommendationCovers = RecommendationCoverCache<android.graphics.Bitmap>(
        capacity = 64,
        maxBytes = 64L * 1024L * 1024L,
        weight = { it.allocationByteCount.toLong() },
    )
    private val artistAvatars = LinkedHashMap<String, android.graphics.Bitmap>(128, 0.75f, true)
    private var artistAvatarUrl: String? = null
    private var artistAvatarRequestAt = 0L
    private var artistAvatarRequestId = 0L
    private var artistAvatarJob: Job? = null
    private var standbyLogoRequestId = 0L
    private var standbyLogoJob: Job? = null
    private var standbyCarouselEnabled = true
    private var antiBurnEnabled = true
    private var standbyIntervalMs = 8_000L
    private val standbyMotionAnimators = mutableListOf<ObjectAnimator>()
    private var currentPlaybackState = "idle"
    private var hasCurrentSong = false
    private val standbyTicker = object : Runnable {
        override fun run() {
            if (!::binding.isInitialized || binding.standbyPanel.visibility != View.VISIBLE) return
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
            lifecycleScope.launch { mediaApi.fetchStandbyContent()?.let(::applyStandbyContent) }
            binding.standbyPanel.postDelayed(this, 60_000L)
        }
    }

    private val modeCapabilities by lazy { DeviceModeCapabilities.forMode(config.effectiveMode()) }
    private lateinit var kioskController: KtvKioskOverlayController
    private val kioskCoordinator = KioskModeCoordinator(
        idleTimeoutMs = KioskModeCoordinator.DEFAULT_IDLE_TIMEOUT_MS,
        scheduler = HandlerIdleTimerScheduler(),
    )
    private val focusController = KtvFocusController()
    private val kioskViewModel: ControllerViewModel by lazy {
        ViewModelProvider(
            this,
            ControllerViewModelFactory(application, realtimeEnabled = false),
        )[ControllerViewModel::class.java]
    }
    private lateinit var playbackCoordinator: PlaybackCoordinator

    private var sessionFingerprint: DeviceSessionFingerprint? = null
    private val backExitGate = com.homektv.tv.navigation.BackExitGate()

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkSessionRestart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        sessionFingerprint = DeviceSessionFingerprint(
            serverHost = config.serverHost,
            mode = config.effectiveMode(),
            nickname = config.nicknameFor(),
            instanceId = config.serverForHost(config.serverHost)?.instanceId,
        )
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

        // 沉浸式与系统导航条防护：全屏 MV 画面保持无黑边满屏，仅浮层面板避让手势条与挖孔
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            binding.kioskOverlay.kioskRootOverlay.setPadding(
                systemBars.left, systemBars.top, systemBars.right, systemBars.bottom
            )
            binding.remoteMenu.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            binding.queueOverlay.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        clock.post(clockTick)

        binding.txtAddress.text = config.h5Url()

        mediaApi = MediaApi(config)
        playbackCoordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long) = mediaApi.resolveFileSource(songId)
                override fun streamUrl(fileId: Long): String = mediaApi.streamUrl(fileId)
            },
            desiredState = desiredPlaybackState,
            scope = lifecycleScope,
            onBeginReplacement = {
                // Invalidate the old output before waiting on the next REST
                // resolution. The coordinator owns retries; this callback only
                // performs the synchronous player-side stop.
                playbackLoadJob?.cancel()
                playbackLoadJob = null
                engine?.stop()
                loadedQueueId = null
                currentFileId = null
            },
            onFileReady = { token, file, snapshot, streamUrl ->
                onPlaybackFileReady(token, file, snapshot, streamUrl)
            },
            onMissingSource = { token ->
                val ticket = activePlaybackLoadTicket
                if (ticket != null && playbackCoordinator.isCurrent(token) && isCurrentPlaybackLoad(ticket)) {
                    onPlayError(PlaybackErrorContext.missingSource(token.request.queueId))
                }
            },
            onWaitingForSource = { token, _ ->
                if (playbackCoordinator.isCurrent(token)) onToast(getString(R.string.play_waiting_network))
            },
            onSourceFailure = { token, error ->
                if (playbackCoordinator.isCurrent(token)) {
                    val message = if (error.status == 401 || error.status == 403) {
                        "点歌服务凭据无效，请重新配置"
                    } else {
                        "歌曲详情协议异常，已停止自动播放"
                    }
                    onToast(message)
                }
            },
            onSourceExhausted = { token, _ ->
                if (playbackCoordinator.isCurrent(token)) {
                    onToast(getString(R.string.play_waiting_network))
                    onPlayError(PlaybackSourceFailurePolicy.errorContext(token))
                }
            },
        )
        updateManager = AndroidUpdateManager(this, mediaApi) { onToast(it) }
        if (config.isConfigured) {
            updateManager.checkForUpdate(lifecycleScope)
        }
        loadQr()
        loadStandbyContent()
        startStandbyMotion()
        binding.standbyPanel.post(standbyTicker)
        binding.standbyPanel.postDelayed(burnInTicker, 60_000L)
        binding.standbyPanel.post(standbySettingsTicker)
        engine = PlaybackEngine(
            context = this,
            onProgress = { pos, queueId ->
                // UI 以高频本地时钟平滑刷新，服务端进度仍保持 1s 上报频率。
                if (lastProgressReportMs == Long.MIN_VALUE || pos - lastProgressReportMs >= 1_000L) {
                    lastProgressReportMs = pos
                    socket?.sendProgress(pos, queueId)
                }
                runOnUiThread { updateProgress(pos) }
            },
            onFinished = { queueId -> socket?.sendFinished(queueId) },
            onError = { _, context -> onPlayError(context) },
        ).also {
            it.attach(binding.playerView)
            // 遥控音量键在此 ROM 上直达系统媒体会话：监听系统音量变化上行同步服务端
            it.onExternalVolumeChange = { percent ->
                if (percent != currentVolume) sendControl("set_volume", "{\"volume\":$percent}")
            }
        }
        presentationController = KtvPresentationController(this) { externalPlayerView ->
            if (externalPlayerView == null) {
                externalDisplayActive = false
                if (::kioskController.isInitialized) kioskController.updateExternalDisplay(false)
                attachedExternalPlayerView?.let { engine?.detach(it) }
                attachedExternalPlayerView = null
                engine?.detach(binding.playerView)
                engine?.attach(binding.playerView)
                if (hasCurrentSong && currentPlaybackState != "idle") {
                    binding.playerView.visibility = View.VISIBLE
                }
            } else {
                externalDisplayActive = true
                if (::kioskController.isInitialized) kioskController.updateExternalDisplay(true)
                attachedExternalPlayerView
                    ?.takeUnless { it === externalPlayerView }
                    ?.let { engine?.detach(it) }
                engine?.detach(binding.playerView)
                engine?.attach(externalPlayerView)
                attachedExternalPlayerView = externalPlayerView
                if (hasCurrentSong && currentPlaybackState != "idle") {
                    // Keep the main screen available for the point-song UI while the
                    // external Presentation owns the video surface.
                    binding.playerView.visibility = View.INVISIBLE
                }
            }
        }.also { it.start() }
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
        if (modeCapabilities.canOpenKiosk) {
            kioskController = KtvKioskOverlayController(
                activity = this,
                binding = binding,
                coordinator = kioskCoordinator,
                focusController = focusController,
                effectPlayer = effectPlayer,
                controllerActions = kioskViewModel,
                catalogActions = kioskViewModel,
                personalActions = kioskViewModel,
                initialExternalDisplayActive = externalDisplayActive,
                onTogglePlayback = { togglePlayback() },
                onNext = { sendControl("next") },
                onRestart = { sendControl("restart") },
                onToggleVocal = { toggleVocal() },
            )
            if (KioskLifecyclePolicy.shouldAutoLaunchKioskOnStart(config.effectiveMode(), modeCapabilities.canOpenKiosk)) {
                binding.root.post {
                    if (::kioskController.isInitialized) {
                        binding.standbyPanel.visibility = View.GONE
                        kioskController.toggleKiosk(true)
                    }
                }
            }
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
        updateArtistAvatar(null)
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

    override fun onResume() {
        super.onResume()
        checkSessionRestart()
        if (config.isConfigured) {
            checkModeMigration()
        }
    }

    private fun checkSessionRestart() {
        val previous = sessionFingerprint ?: return
        val currentConfig = AppConfig(this)
        val current = DeviceSessionFingerprint(
            serverHost = currentConfig.serverHost,
            mode = currentConfig.effectiveMode(),
            nickname = currentConfig.nicknameFor(),
            instanceId = currentConfig.serverForHost(currentConfig.serverHost)?.instanceId,
        )
        if (DeviceSessionChangePolicy.requiresRestart(previous, current)) {
            if (current.mode == DeviceMode.CONTROLLER) {
                startActivity(Intent(this, ControllerActivity::class.java))
                finish()
                return
            }
            recreate()
        }
    }

    private fun checkModeMigration() {
        val savedMode = config.modeFor()
        val action = DeviceModeMigrationPolicy.evaluate(
            savedMode = savedMode,
            recommendedMode = config.recommendedMode,
            migrationVersion = config.getModeMigrationVersion(),
        )
        if (action == DeviceModeMigrationPolicy.Action.PROMPT_COMBINED &&
            AndroidUpdatePolicy.isHostActivityAlive(isFinishing, isDestroyed)
        ) {
            AlertDialog.Builder(this)
                .setTitle("新功能提示")
                .setMessage("新版本已支持电视大屏直接点歌（播放+点歌台合一）。\n推荐切换为“播放+点歌”模式，您也可以随时在遥控菜单“服务器/模式”中更改。")
                .setPositiveButton("切换为播放+点歌") { _, _ ->
                    config.setModeMigrationVersion(DeviceModeMigrationPolicy.CURRENT_VERSION)
                    config.saveMode(DeviceMode.COMBINED)
                    recreate()
                }
                .setNegativeButton("保持仅播放") { _, _ ->
                    config.setModeMigrationVersion(DeviceModeMigrationPolicy.CURRENT_VERSION)
                }
                .setOnCancelListener {
                    config.setModeMigrationVersion(DeviceModeMigrationPolicy.CURRENT_VERSION)
                }
                .show()
        }
    }

    override fun onDestroy() {
        standbyMotionAnimators.forEach(ObjectAnimator::cancel)
        standbyMotionAnimators.clear()
        invalidatePlaybackLoad()
        socket?.close()
        presentationController?.stop()
        presentationController = null
        attachedExternalPlayerView?.let { engine?.detach(it) }
        attachedExternalPlayerView = null
        clock.removeCallbacks(clockTick)
        clock.removeCallbacks(progressHide)
        standbyLogoJob?.cancel()
        standbyLogoJob = null
        if (::binding.isInitialized) {
            binding.standbyPanel.removeCallbacks(standbyTicker)
            binding.standbyPanel.removeCallbacks(burnInTicker)
            binding.standbyPanel.removeCallbacks(standbySettingsTicker)
        }
        socket = null
        if (::binding.isInitialized) engine?.detach(binding.playerView)
        engine?.release()
        engine = null
        if (::mediaApi.isInitialized) mediaApi.close()
        effectPlayer?.release()
        effectPlayer = null
        effectOverlay?.clear()
        effectOverlay = null
        microphoneMonitor?.release()
        microphoneMonitor = null
        if (::kioskController.isInitialized) {
            kioskController.destroy()
        }
        super.onDestroy()
    }

    /** 顶层 BACK 双击退出：此 ROM 会在 Activity 退到后台 1s 内强杀进程，单击防误触。 */
    private var lastBackAt = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::kioskController.isInitialized && kioskController.dispatchKeyEvent(event)) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && binding.playerView.visibility == View.VISIBLE) {
            showPlaybackProgress()
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (::kioskController.isInitialized && focusController.shouldInterceptMenu()) {
                return true
            }
            hideVocalPanel()
            if (modeCapabilities.canOpenKiosk && ::kioskController.isInitialized) {
                kioskController.toggleKiosk(true)
                return true
            }
            binding.remoteMenu.visibility = if (binding.remoteMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (binding.remoteMenu.visibility == View.VISIBLE) binding.remotePlay.requestFocus()
            resetMenuTimer()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            backExitGate.reset()
        }
        if (binding.remoteMenu.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) resetMenuTimer()
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    binding.remoteMenu.visibility = View.GONE
                    backExitGate.reset()
                }
                return true
            }
        }
        if (binding.queueOverlay.visibility == View.VISIBLE && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                binding.queueOverlay.visibility = View.GONE
                backExitGate.reset()
            }
            return true
        }
        // 原/伴唱选择栏：BACK 收起；可见期间方向键/确认键交给焦点系统（移动选择、点击生效）
        if (binding.vocalPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    hideVocalPanel()
                    backExitGate.reset()
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) resetVocalTimer()
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
            if (binding.remoteMenu.visibility != View.VISIBLE && binding.queueOverlay.visibility != View.VISIBLE && !kioskCoordinator.isKioskActive.value) {
                when (event.keyCode) {
                    // 确认键：呼出点歌台（MV 转入画中画），若无点歌能力则回退弹出原唱/伴唱选择栏
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (modeCapabilities.canOpenKiosk && ::kioskController.isInitialized) {
                            kioskController.toggleKiosk(true)
                            return true
                        }
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                val decision = backExitGate.onBack(android.os.SystemClock.elapsedRealtime(), isAtTopLevel = true)
                when (decision) {
                    com.homektv.tv.navigation.BackExitDecision.CONSUMED_AND_PROMPTED -> {
                        Toast.makeText(this, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show()
                    }
                    com.homektv.tv.navigation.BackExitDecision.EXIT_APP -> {
                        finish()
                    }
                    com.homektv.tv.navigation.BackExitDecision.IGNORED -> {}
                }
            }
            return true
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

    // ---- 原伴唱 HUD ----

    private val vocalOsdHide = Runnable { binding.vocalOsd.visibility = View.GONE }

    /** 切换原伴唱时屏幕中央浮现 1.5s 金色 HUD。 */
    private fun showVocalOsd(mode: String) {
        binding.txtVocalOsd.text = VocalTogglePolicy.resolveOsdText(mode)
        binding.vocalOsd.visibility = View.VISIBLE
        binding.vocalOsd.removeCallbacks(vocalOsdHide)
        binding.vocalOsd.postDelayed(vocalOsdHide, 1_500L)
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
        binding.remoteQueue.setOnClickListener {
            if (LegacyQueuePolicy.shouldOpenKioskDrawer(modeCapabilities.canOpenKiosk, ::kioskController.isInitialized)) {
                binding.remoteMenu.visibility = View.GONE
                kioskController.openQueueDrawer()
            } else {
                showQueueOverlay()
            }
        }
        binding.remoteOrder.setOnClickListener {
            binding.remoteMenu.visibility = View.GONE
            if (modeCapabilities.canOpenKiosk && ::kioskController.isInitialized) {
                kioskController.toggleKiosk(true)
            } else {
                startActivity(Intent(this, ControllerActivity::class.java).apply {
                    putExtra(ControllerFragment.EXTRA_REALTIME, false)
                })
            }
        }
        binding.remoteOrder.visibility = if (modeCapabilities.canOpenKiosk) View.VISIBLE else View.GONE
        binding.remoteMicrophone.setOnClickListener { toggleMicrophoneMonitor() }
        updateMicrophoneButton()
        binding.remoteSettings.setOnClickListener {
            binding.remoteMenu.visibility = View.GONE
            settingsLauncher.launch(Intent(this, SetupActivity::class.java).apply {
                putExtra(SetupActivity.EXTRA_FORCE_SETUP, true)
                putExtra(SetupActivity.EXTRA_RETURN_TO_CALLER, true)
            })
        }
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
        val nextMode = VocalTogglePolicy.toggleMode(currentVocalMode)
        showVocalOsd(nextMode)
        sendControl("set_vocal", "{\"mode\":\"$nextMode\"}")
    }

    private fun changeVolume(delta: Int) {
        val volume = (currentVolume + delta).coerceIn(0, 100)
        sendControl("set_volume", "{\"volume\":$volume}")
    }

    private fun showQueueOverlay() {
        binding.remoteMenu.visibility = View.GONE
        cachedQueueSnapshot?.let { renderQueue(it) }
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
        playerConnected = connected
        if (!connected) lastSnapshotRevision = 0L
        playerConnectionStatus = PlayerConnectionStatusPolicy.onConnectionChanged(connected, playerRoleActive)
        renderPlayerConnectionStatus()
        if (connected && ::updateManager.isInitialized) updateManager.checkForUpdate(lifecycleScope)
    }

    override fun onPlayerRole(active: Boolean) {
        playerRoleActive = active
        playerConnectionStatus = PlayerConnectionStatusPolicy.onPlayerRole(active, playerConnected)
        renderPlayerConnectionStatus()
        if (active) return
        invalidatePlaybackLoad()
        currentQueueId = null
        loadedQueueId = null
        currentFileId = null
        engine?.stop()
        showStandby()
    }

    private fun renderPlayerConnectionStatus() {
        binding.txtStatus.setText(
            when (playerConnectionStatus) {
                PlayerConnectionStatus.CONNECTING -> R.string.status_connecting
                PlayerConnectionStatus.ONLINE -> R.string.status_connected
                PlayerConnectionStatus.STANDBY -> R.string.status_standby
            },
        )
    }

    private var snapshotReceived = false
    private var lastSnapshotRevision = 0L
    private var cachedQueueSnapshot: QueueSnapshot? = null
    private var playerConnectionStatus = PlayerConnectionStatus.CONNECTING
    private var playerConnected = false
    private var playerRoleActive = true

    override fun onSnapshot(event: String, snapshot: QueueSnapshot) {
        if (!SnapshotRevisionPolicy.accepts(lastSnapshotRevision, snapshot.stateRevision)) return
        if (snapshot.stateRevision > lastSnapshotRevision) {
            lastSnapshotRevision = snapshot.stateRevision
        }
        // Metadata/cover requests below may suspend for several seconds. Always publish the
        // newest complete server state before starting or continuing that asynchronous work.
        desiredPlaybackState.update(snapshot)
        // 音量/静音变化（遥控音量键、H5、遥控菜单任何来源）→ 顶部 OSD；首个快照不弹
        if (snapshotReceived && (snapshot.volume != currentVolume || snapshot.muted != currentMuted)) {
            showVolumeOsd(snapshot.volume, snapshot.muted)
        }
        if (snapshotReceived && !snapshot.vocalMode.equals(currentVocalMode, ignoreCase = true)) {
            showVocalOsd(snapshot.vocalMode)
        }
        snapshotReceived = true
        currentVolume = snapshot.volume
        currentMuted = snapshot.muted
        if (::kioskController.isInitialized) {
            kioskController.updateSnapshot(snapshot)
        }
        currentVocalMode = snapshot.vocalMode
        currentAudioLayout = snapshot.audioLayout
        if (binding.vocalPanel.visibility == View.VISIBLE) updateVocalPanelSelection()
        currentPlaybackState = snapshot.state
        val lyricsPlaying = snapshot.state == "playing"
        binding.txtLyricPrevious.updatePlayback(engine?.currentPositionMs ?: 0L, lyricsPlaying)
        binding.txtAudioLyricCurrent.updatePlayback(engine?.currentPositionMs ?: 0L, lyricsPlaying)
        hasCurrentSong = snapshot.playing != null
        cachedQueueSnapshot = snapshot
        if (binding.queueOverlay.visibility == View.VISIBLE) {
            renderQueue(snapshot)
        }
        binding.txtPhones.text =
            getString(R.string.status_phones, snapshot.connectedPhones.toInt())
        binding.txtWaitingStat.text = "排队中 ${StandbyStatsPolicy.waitingCount(snapshot)} 首"
        binding.txtWaitingStat.visibility = View.VISIBLE
        binding.txtPlayedStat.visibility = View.GONE
        applyPlayback(snapshot)
        if (event == VOCAL_CHANGED_EVENT) refreshVocalTrackMapping(snapshot)
    }

    private fun renderQueue(snapshot: QueueSnapshot) {
        val now = snapshot.playing?.song
        binding.queueNow.text = if (now == null) "当前演唱：暂无" else "正在演唱  ${now.title} · ${now.artist}"
        val focusedQueueId = (0 until binding.queueList.childCount)
            .map { binding.queueList.getChildAt(it) }
            .firstOrNull { it.hasFocus() }
            ?.tag as? Long
        val focusedIndex = (0 until binding.queueList.childCount)
            .indexOfFirst { binding.queueList.getChildAt(it).hasFocus() }

        binding.queueList.removeAllViews()
        snapshot.list.forEachIndexed { index, item ->
            val song = item.song ?: return@forEachIndexed
            val row = TextView(this).apply {
                tag = item.queueId
                text = "%02d    %s · %s    %s".format(index + 1, song.title, song.artist, item.orderedByNick ?: "")
                textSize = 20f
                setTextColor(getColor(R.color.dim))
                setPadding(18, 20, 18, 20)
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                if (LegacyQueuePolicy.shouldAllowMutations(modeCapabilities.canOpenKiosk)) {
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
            }
            binding.queueList.addView(row)
        }
        if (binding.queueOverlay.visibility == View.VISIBLE) {
            val targetFocus = if (focusedQueueId != null) {
                (0 until binding.queueList.childCount)
                    .map { binding.queueList.getChildAt(it) }
                    .firstOrNull { it.tag == focusedQueueId }
            } else null

            if (targetFocus != null) {
                targetFocus.requestFocus()
            } else if (focusedIndex >= 0 && binding.queueList.childCount > 0) {
                val adjacentIndex = focusedIndex.coerceIn(0, binding.queueList.childCount - 1)
                binding.queueList.getChildAt(adjacentIndex)?.requestFocus()
            } else if (binding.queueOverlay.findFocus() == null) {
                (binding.queueList.getChildAt(0) ?: binding.queueClose).requestFocus()
            }
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
            invalidatePlaybackLoad()
            currentQueueId = null
            loadedQueueId = null
            currentFileId = null
            playbackSeekGate.reset()
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
        if (playing.queueId == currentQueueId &&
            (playbackReplacementJob?.isActive == true || playbackLoadJob?.isActive == true)
        ) {
            return
        }
        if (playing.queueId == currentQueueId && loadedQueueId == playing.queueId) {
            eng.applyVolume(volume, muted)
            eng.setVocalMode(snapshot.vocalMode, accompanimentTrackIndex, audioTrackCount, currentAudioLayout)
            if (playbackSeekGate.shouldApply(snapshot.seekSequence)) {
                eng.seekTo(snapshot.positionMs)
                playbackSeekGate.markApplied(snapshot.seekSequence)
                lastLyricIndex = -1
                updateProgress(snapshot.positionMs)
            }
            if (snapshot.state == "paused") eng.pause() else eng.resume()
            return
        }

        // 换歌：拉详情取文件源 → 播放
        currentQueueId = playing.queueId
        loadedQueueId = null
        currentFileId = null
        val targetQueueId = playing.queueId
        activePlaybackLoadTicket = beginPlaybackLoad(targetQueueId)
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
        val replacement = playbackCoordinator.replace(
            PlaybackReplacementRequest(queueId = targetQueueId, songId = songId),
        )
        playbackReplacementJob = replacement.job
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::kioskController.isInitialized) kioskCoordinator.resetIdleTimer()
    }

    private fun onPlaybackFileReady(
        token: PlaybackReplacementToken,
        file: com.homektv.tv.net.FileSource,
        snapshot: QueueSnapshot,
        streamUrl: String,
    ) {
        val loadTicket = activePlaybackLoadTicket ?: return
        if (!playbackCoordinator.isCurrent(token) || !isCurrentPlaybackLoad(loadTicket)) return
        playbackReplacementJob = null
        val targetQueueId = token.request.queueId
        val songId = token.request.songId
        val audioMode = snapshot.playing?.song?.mediaType.equals("AUDIO", ignoreCase = true)
        val eng = engine ?: return
        accompanimentTrackIndex = file.audioLayout.accompanimentTrackIndex ?: file.vocalTrackIndex
        audioTrackCount = file.audioTracks
        currentAudioLayout = file.audioLayout
        currentFileId = file.id
        playbackLoadJob = lifecycleScope.launch {
            lyricLines = mediaApi.fetchLyric(songId)?.let(LrcParser::parse).orEmpty()
            if (!playbackCoordinator.isCurrent(token) || !isCurrentPlaybackLoad(loadTicket)) return@launch
            if (audioMode) {
                val coverBytes = mediaApi.fetchCover(songId)
                if (!playbackCoordinator.isCurrent(token) || !isCurrentPlaybackLoad(loadTicket)) return@launch
                coverBytes?.let { bytes ->
                    val bitmap = withContext(Dispatchers.Default) {
                        ArtworkDecoder.decode(bytes, ArtworkProfile.COVER)
                    }
                    if (bitmap != null && playbackCoordinator.isCurrent(token) && isCurrentPlaybackLoad(loadTicket)) {
                        binding.imgAudioCover.setImageBitmap(bitmap)
                    }
                }
                if (lyricLines.isNotEmpty()) binding.txtAudioLyricNext.text = lyricLines.first().text
            }
            if (!playbackCoordinator.isCurrent(token) || !isCurrentPlaybackLoad(loadTicket)) return@launch
            val command = playbackLoadProjection.commandForLoadedFile(
                queueId = targetQueueId,
                fileId = file.id,
                streamUrl = streamUrl,
                accompanimentTrackIndex = accompanimentTrackIndex,
                audioTrackCount = audioTrackCount,
                audioLayout = currentAudioLayout,
            ) ?: return@launch
            eng.setVocalMode(
                command.vocalMode,
                command.accompanimentTrackIndex,
                command.audioTrackCount,
                command.audioLayout,
            )
            eng.applyVolume(command.volume, command.muted)
            if (!playbackCoordinator.isCurrent(token) || !isCurrentPlaybackLoad(loadTicket)) return@launch
            val hasVideoDeclared = !snapshot.playing?.song?.mediaType.equals("AUDIO", ignoreCase = true) && !file.resolution.isNullOrBlank()
            eng.play(
                command.fileId,
                command.streamUrl,
                command.queueId,
                command.playWhenReady,
                command.positionMs,
                hasVideoDeclared = hasVideoDeclared,
                format = file.format,
            )
            if (!playbackCoordinator.isCurrent(token) || !isCurrentPlaybackLoad(loadTicket)) return@launch
            loadedQueueId = targetQueueId
            playbackSeekGate.markApplied(command.seekSequence)
        }
    }

    private fun beginPlaybackLoad(queueId: Long?): PlaybackLoadTicket {
        playbackLoadJob?.cancel()
        playbackLoadJob = null
        playbackReplacementJob?.cancel()
        playbackReplacementJob = null
        return playbackLoadGate.begin(queueId)
    }

    private fun isCurrentPlaybackLoad(ticket: PlaybackLoadTicket): Boolean =
        playbackLoadGate.isCurrent(ticket)

    private fun invalidatePlaybackLoad() {
        playbackLoadJob?.cancel()
        playbackLoadJob = null
        playbackReplacementJob?.cancel()
        playbackReplacementJob = null
        activePlaybackLoadTicket = null
        playbackLoadGate.invalidate()
        if (::playbackCoordinator.isInitialized) playbackCoordinator.invalidate()
    }

    private fun refreshVocalTrackMapping(snapshot: QueueSnapshot) {
        val playing = snapshot.playing ?: return
        val songId = playing.song?.id ?: return
        val targetQueueId = playing.queueId
        lifecycleScope.launch {
            val file = mediaApi.bestFileSource(songId) ?: return@launch
            if (loadedQueueId != targetQueueId || currentFileId != file.id) return@launch
            accompanimentTrackIndex = file.audioLayout.accompanimentTrackIndex ?: file.vocalTrackIndex
            audioTrackCount = file.audioTracks
            currentAudioLayout = file.audioLayout
            val latest = desiredPlaybackState.forQueue(targetQueueId) ?: return@launch
            engine?.setVocalMode(latest.vocalMode, accompanimentTrackIndex, audioTrackCount, currentAudioLayout)
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
                withContext(Dispatchers.Default) {
                    ArtworkDecoder.decode(it, ArtworkProfile.QR)
                }
            }
            if (bmp != null) {
                binding.imgQr.setImageBitmap(bmp)
                binding.imgMiniQr.setImageBitmap(bmp)
                binding.imgAudioMiniQr.setImageBitmap(bmp)
                binding.imgQr.visibility = View.VISIBLE
                binding.txtQrPlaceholder.visibility = View.GONE
                if (::kioskController.isInitialized) {
                    kioskController.setCachedQrBitmap(bmp)
                }
            }
        }
    }

    private fun loadStandbyContent() {
        lifecycleScope.launch {
            val content = mediaApi.fetchStandbyContent()
            content?.let(::applyStandbyContent)
            val libraryCount = mediaApi.fetchLibraryCount()
            binding.txtLibraryStat.visibility = if (libraryCount == null) View.GONE else View.VISIBLE
            if (libraryCount != null) binding.txtLibraryStat.text = "曲库 $libraryCount 首"
            binding.recommendationRow.visibility = if (recommendations.isEmpty()) View.GONE else View.VISIBLE
            binding.txtRecommendationsEmpty.visibility = if (recommendations.isEmpty()) View.VISIBLE else View.GONE
            if (recommendations.isNotEmpty()) renderRecommendationCards()
        }
    }

    private fun applyStandbyContent(content: StandbyContent) {
        standbyLogoJob?.cancel()
        val logoRequestId = ++standbyLogoRequestId
        applyVideoScaleMode(content.videoScaleMode)
        standbyCarouselEnabled = content.carouselEnabled
        antiBurnEnabled = content.antiBurn
        standbyIntervalMs = content.intervalSeconds.coerceIn(3, 60) * 1_000L
        val miniQrVisibility = if (StandbyQrPolicy.visibility(content.miniQr).imageVisible) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val miniQrLabelVisibility = if (StandbyQrPolicy.visibility(content.miniQr).labelVisible) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.imgMiniQr.visibility = miniQrVisibility
        binding.txtMiniQrHint.visibility = miniQrLabelVisibility
        binding.imgAudioMiniQr.visibility = miniQrVisibility
        binding.txtAudioMiniQrHint.visibility = miniQrLabelVisibility
        binding.txtStandbyWelcome.text = content.welcomeText
        binding.txtStandbySubtitle.text = content.subtitle
        recommendations = content.songs.sortedByDescending { it.coverUrl != null }
        binding.recommendationRow.visibility = if (recommendations.isEmpty()) View.GONE else View.VISIBLE
        binding.txtRecommendationsEmpty.visibility = if (recommendations.isEmpty()) View.VISIBLE else View.GONE
        if (recommendations.isNotEmpty()) renderRecommendationCards()
        val logoUrl = content.logoUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (logoUrl == null) {
            binding.imgStandbyLogo.setImageResource(R.drawable.home_ktv_logo)
            binding.imgStandbyLogo.visibility = View.VISIBLE
            binding.txtBrandName.visibility = View.VISIBLE
        } else {
            standbyLogoJob = lifecycleScope.launch {
                val bitmap = mediaApi.fetchUrl(logoUrl)?.let {
                    withContext(Dispatchers.Default) {
                        ArtworkDecoder.decode(it, ArtworkProfile.LOGO)
                    }
                }
                if (logoRequestId != standbyLogoRequestId) return@launch
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
                recommendationCovers.get(song.id)?.let(::setImageBitmap)
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

            if (song.coverUrl != null && recommendationCovers.tryStartLoad(song.id)) {
                lifecycleScope.launch {
                    try {
                        val bytes = mediaApi.fetchCover(song.id)
                        val bitmap = withContext(Dispatchers.Default) {
                            bytes?.let {
                                ArtworkDecoder.decode(it, ArtworkProfile.COVER)
                            }
                        }
                        if (bitmap != null) {
                            recommendationCovers.complete(song.id, bitmap)
                            if (song in visible) cover.setImageBitmap(bitmap)
                        } else {
                            recommendationCovers.fail(song.id)
                        }
                    } catch (e: Exception) {
                        recommendationCovers.fail(song.id)
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showPlayer(audioMode: Boolean = false) {
        binding.standbyPanel.removeCallbacks(standbyTicker)
        binding.playerView.visibility = if (externalDisplayActive) View.INVISIBLE else View.VISIBLE
        binding.standbyPanel.visibility = View.GONE
        binding.ktvOverlay.visibility = if (audioMode) View.GONE else View.VISIBLE
        binding.audioOverlay.visibility = if (audioMode) View.VISIBLE else View.GONE
        showPlaybackProgress()
    }

    private fun showStandby() {
        binding.standbyPanel.removeCallbacks(standbyTicker)
        binding.playerView.visibility = View.GONE
        binding.ktvOverlay.visibility = View.GONE
        binding.audioOverlay.visibility = View.GONE
        hidePlaybackProgress()
        if (modeCapabilities.canOpenKiosk && ::kioskController.isInitialized) {
            binding.standbyPanel.visibility = View.GONE
            kioskController.toggleKiosk(true)
        } else {
            binding.standbyPanel.visibility = View.VISIBLE
            binding.standbyPanel.post(standbyTicker)
        }
    }

    private fun showPlaybackProgress() {
        if (!::binding.isInitialized ||
            (binding.playerView.visibility != View.VISIBLE && !externalDisplayActive)
        ) return
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
        updateArtistAvatar(song.artistAvatarUrl)
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
        binding.txtVocalMode.text = if (supportsVocalSwitch(snapshot.audioLayout, audioTrackCount)) {
            if (snapshot.vocalMode == "original") "原唱中" else "伴唱中"
        } else ""
        binding.txtDuration.text = formatMs(song.durationMs.toLong())
    }

    /** Loads the cached server avatar without blocking playback or allowing stale songs to win. */
    private fun updateArtistAvatar(rawUrl: String?) {
        if (!::binding.isInitialized) return
        val url = rawUrl?.trim()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val cached = url?.let { artistAvatars[it] }
        if (url == artistAvatarUrl && (cached != null || now - artistAvatarRequestAt < 30_000L)) return

        artistAvatarUrl = url
        artistAvatarRequestAt = now
        val requestId = ++artistAvatarRequestId
        artistAvatarJob?.cancel()
        artistAvatarJob = null
        binding.imgPlayerArtistAvatar.setImageDrawable(null)
        binding.imgPlayerArtistAvatar.visibility = View.GONE
        binding.imgAudioArtistAvatar.setImageDrawable(null)
        binding.imgAudioArtistAvatar.visibility = View.GONE
        if (url == null) return

        if (cached != null) {
            showArtistAvatar(cached)
            return
        }
        artistAvatarJob = lifecycleScope.launch {
            val bitmap = mediaApi.fetchUrl(url)?.let { bytes ->
                withContext(Dispatchers.Default) {
                    ArtworkDecoder.decode(bytes, ArtworkProfile.AVATAR)
                }
            }
            if (bitmap != null) {
                artistAvatars[url] = bitmap
                if (artistAvatars.size > 128) artistAvatars.remove(artistAvatars.entries.first().key)
            }
            if (requestId != artistAvatarRequestId || url != artistAvatarUrl) return@launch
            if (bitmap != null) showArtistAvatar(bitmap)
        }
    }

    private fun showArtistAvatar(bitmap: android.graphics.Bitmap) {
        binding.imgPlayerArtistAvatar.setImageBitmap(bitmap)
        binding.imgPlayerArtistAvatar.visibility = View.VISIBLE
        binding.imgAudioArtistAvatar.setImageBitmap(bitmap)
        binding.imgAudioArtistAvatar.visibility = View.VISIBLE
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

    /** 播放失败：通知服务端推进队列，不将一次客户端播放失败视为源文件失效。 */
    private fun onPlayError(context: PlaybackErrorContext) {
        Toast.makeText(this, R.string.play_error, Toast.LENGTH_SHORT).show()
        socket?.sendPlayError("media playback failed", context.fileId, context.queueId)
    }

    companion object {
        /** 二维码请求边长（服务端在 [120,1080] 内钳制）；待机占位框 220dp，取 540 兼顾高密度电视清晰度。 */
        private const val QR_SIZE_PX = 540
        private const val PROGRESS_HIDE_DELAY_MS = 5_000L
        private const val VOCAL_CHANGED_EVENT = "vocal_changed"
    }
}
