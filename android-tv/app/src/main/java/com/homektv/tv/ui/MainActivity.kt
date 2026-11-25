package com.homektv.tv.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
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
import com.homektv.tv.session.DeviceSessionChangePolicy
import com.homektv.tv.session.DeviceSessionFingerprint
import com.homektv.tv.ui.kiosk.KtvDashboardBackground
import com.homektv.tv.ui.kiosk.KtvDashboardPhotoCarousel
import com.homektv.tv.R
import com.homektv.tv.databinding.ActivityMainBinding
import com.homektv.tv.databinding.ViewAudioOverlayBinding
import com.homektv.tv.databinding.ViewRemoteMenuBinding
import com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
import com.homektv.tv.databinding.ViewKtvBottomDockBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.KtvSocket
import com.homektv.tv.net.MediaApi
import com.homektv.tv.net.MicrophoneMonitorStartupPolicy
import com.homektv.tv.net.ApkPackageInfo
import com.homektv.tv.net.PlaybackErrorContext
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SnapshotRevisionPolicy
import com.homektv.tv.net.StandbyContent
import com.homektv.tv.player.PlaybackEngine
import com.homektv.tv.player.PlaybackPitchResult
import com.homektv.tv.player.PlaybackPitchStatus
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
import com.homektv.tv.player.LiveTranscodeStreamUrl
import com.homektv.tv.player.PlaybackSeekGate
import com.homektv.tv.player.supportsVocalSwitch
import com.homektv.tv.player.VocalMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
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
    private lateinit var bottomDockController: KtvBottomDockController
    private lateinit var fullscreenPlaybackPanel: KtvFullscreenPlaybackPanel
    private var audioOverlayBinding: ViewAudioOverlayBinding? = null
    private var remoteMenuBinding: ViewRemoteMenuBinding? = null
    private var remoteMenuBasePadding = intArrayOf(0, 0, 0, 0)
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
        else {
            config.microphoneMonitorEnabled = false
            microphoneActive = false
            updateMicrophoneButton()
            onToast("未授予麦克风权限")
        }
    }

    /** 当前请求中的 queueId，用于判断快照是否切了歌；播放器回调使用其已加载身份。 */
    private var currentQueueId: Long? = null
    /** 只有 eng.play 已提交后才设置，避免加载中的新快照操作旧媒体。 */
    private var loadedQueueId: Long? = null
    private var currentFileId: Long? = null
    private val playbackLoadGate = PlaybackLoadGate()
    private var playbackAssetsJob: Job? = null
    private var playbackReplacementJob: Job? = null
    private var activePlaybackLoadTicket: PlaybackLoadTicket? = null
    private val desiredPlaybackState = DesiredPlaybackState()
    private val playbackLoadProjection = PlaybackLoadProjection(desiredPlaybackState)
    private val playbackSeekGate = PlaybackSeekGate()
    private var accompanimentTrackIndex: Int? = null
    private var audioTrackCount: Int = 1
    private var lyricLines: List<LyricLine> = emptyList()
    private var lastLyricIndex = -1
    private var lastElapsedSecond = -1L
    private var lastAudioDurationSecond = -1L
    private val progressReportGate = ProgressReportGate()
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTimeFormat by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val clockDateFormat by lazy { SimpleDateFormat("yyyy/MM/dd", Locale.CHINA) }
    private val clockWeekdayFormat by lazy { SimpleDateFormat("EEEE", Locale.CHINA) }
    private val clockTick = object : Runnable {
        override fun run() {
            if (::binding.isInitialized) {
                val now = Date()
                binding.txtClock.text = clockTimeFormat.format(now)
                binding.txtHeaderDate.text = clockDateFormat.format(now)
                binding.txtHeaderWeekday.text = clockWeekdayFormat.format(now)
            }
            clock.postDelayed(this, 30_000L)
        }
    }
    private val menuHide = Runnable { remoteMenuBinding?.root?.visibility = View.GONE }
    private val progressHide = Runnable { hidePlaybackProgress() }
    private val marqueeNotificationController = KtvMarqueeNotificationController()
    private var activeMarqueeNotification: KtvMarqueeNotificationController.Notification? = null
    private var baselineMarqueeText = ""
    private val marqueeNotificationDismiss = Runnable { dismissMarqueeNotification() }
    private val avatarCacheMaxBytes: Long by lazy { resolveAvatarCacheBudgetBytes() }
    private val sharedAvatarCache: WeightedLruCache<String, android.graphics.Bitmap> by lazy {
        WeightedLruCache(
            capacity = 128,
            maxBytes = avatarCacheMaxBytes,
            weight = { it.allocationByteCount.toLong() },
        )
    }
    private var artistAvatarUrl: String? = null
    private var artistAvatarRequestAt = 0L
    private var artistAvatarRequestId = 0L
    private var artistAvatarJob: Job? = null
    private var currentPlaybackState = "idle"
    private var hasCurrentSong = false
    private val displaySettingsTicker = object : Runnable {
        override fun run() {
            lifecycleScope.launch { mediaApi.fetchStandbyContent()?.let(::applyDisplayContent) }
            binding.root.postDelayed(this, 60_000L)
        }
    }

    private lateinit var kioskController: KtvKioskOverlayController
    private var kioskOverlayBinding: ViewKtvKioskOverlayBinding? = null
    private var kioskWindowInsets = intArrayOf(0, 0, 0, 0)
    private var fullscreenKioskActive = false
    private var audioMiniQrVisibility: Int? = null
    private var audioMiniQrLabelVisibility: Int? = null
    private var cachedKioskQrBitmap: android.graphics.Bitmap? = null
    private var pitchDialog: KtvPitchDialog? = null
    private var topBarQrDialog: KtvQrDialog? = null
    private var remoteMenuReturnFocus: View? = null
    private val activeModalDialogs = mutableListOf<Dialog>()
    private var backgroundFocusSuspended = false
    private var previousRootDescendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    private var qrLoadJob: Job? = null
    private var libraryScanRequestInFlight = false
    private val tvSettingsStore by lazy { KtvTvSettingsStore(applicationContext) }
    private val kioskCoordinator = KioskModeCoordinator(
        idleTimeoutMs = KioskModeCoordinator.DEFAULT_IDLE_TIMEOUT_MS,
        scheduler = HandlerIdleTimerScheduler(),
        onIdleTimeout = ::onKioskIdleTimeout,
    )
    private val focusController = KtvFocusController()
    private val kioskViewModel: ControllerViewModel by lazy {
        ViewModelProvider(
            this,
            ControllerViewModelFactory(application),
        )[ControllerViewModel::class.java]
    }
    private lateinit var playbackCoordinator: PlaybackCoordinator

    private fun ensureKioskController(): KtvKioskOverlayController {
        if (::kioskController.isInitialized) return kioskController

        val overlayBinding = kioskOverlayBinding ?: ViewKtvKioskOverlayBinding
            .bind(binding.kioskOverlayStub.inflate())
            .also { kioskOverlayBinding = it }
        overlayBinding.kioskRootOverlay.setPadding(
            kioskWindowInsets[0],
            kioskWindowInsets[1],
            kioskWindowInsets[2],
            kioskWindowInsets[3],
        )
        kioskController = KtvKioskOverlayController(
            activity = this,
            binding = binding,
            kioskOverlayBinding = overlayBinding,
            bottomDockController = bottomDockController,
            coordinator = kioskCoordinator,
            focusController = focusController,
            controllerActions = kioskViewModel,
            catalogActions = kioskViewModel,
            personalActions = kioskViewModel,
            initialExternalDisplayActive = externalDisplayActive,
            shouldExitOnBack = { tvSettingsStore.snapshot().exitOnBackEnabled },
            globalSearchEnabled = { tvSettingsStore.snapshot().globalSearchEnabled },
            onRequestExitConfirm = ::showExitConfirmDialog,
            sharedAvatarCache = sharedAvatarCache,
        )
        cachedQueueSnapshot?.let(kioskController::updateSnapshot)
        cachedKioskQrBitmap?.let(kioskController::setCachedQrBitmap)
        return kioskController
    }

    private fun createBottomDockController(): KtvBottomDockController {
        val dockBinding = ViewKtvBottomDockBinding.bind(binding.root.findViewById(R.id.ktv_bottom_dock))
        return KtvBottomDockController(
            binding = dockBinding,
            playerContainer = binding.sharedPlayerContainer,
            playerView = binding.playerView,
            txtNowPlaying = binding.txtDockNowPlaying,
            imgQr = binding.imgDockQr,
            actions = KtvBottomDockController.Actions(
                onHome = ::showHomePage,
                onQueue = ::showQueueOverlay,
                onHistory = ::openHistoryTab,
                onCheer = { onEffect("cheer") },
                onToggleVocal = ::toggleVocal,
                onTogglePlayback = ::togglePlayback,
                onRestart = { sendControl("restart") },
                onNext = { sendControl("next") },
                onMixer = ::showMixerPanel,
                onQrCode = ::showQrCode,
                onDownloads = ::showDownloadsDrawer,
                onPitchDown = { onPitchStep(-1) },
                onPitchUp = { onPitchStep(1) },
                onVolumeDelta = ::changeVolume,
            ),
            onFullscreenChanged = ::onDockFullscreenChanged,
            onFullscreenClick = {
                if (::fullscreenPlaybackPanel.isInitialized) {
                    fullscreenPlaybackPanel.showControlsFromClick()
                }
            },
        )
    }

    private var sessionFingerprint: DeviceSessionFingerprint? = null

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkSessionRestart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.homektv.tv.net.SharedHttpClients.installDiskCache(cacheDir)
        config = AppConfig(this)
        sessionFingerprint = DeviceSessionFingerprint(
            serverHost = config.serverHost,
            instanceId = config.serverForHost(config.serverHost)?.instanceId,
            credentialHash = DeviceSessionChangePolicy.fingerprintCredential(config.playerCredential),
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
        bottomDockController = createBottomDockController()
        // SurfaceView layer ordering is a construction-time window policy. Configure it
        // before attaching the Activity content so PlayerView reparenting keeps one stable layer.
        (bottomDockController.playerView.videoSurfaceView as? android.view.SurfaceView)
            ?.setZOrderMediaOverlay(true)
        bottomDockController.render(
            BottomDockUiModel(
                queueCount = 0,
                isPlaying = false,
                vocalMode = currentVocalMode,
                vocalSwitchEnabled = false,
                videoVisible = false,
                nowPlayingLabel = "等待点歌",
                queueCountKnown = false,
                queueSnapshotFresh = false,
            ),
        )
        setContentView(binding.root)
        fullscreenPlaybackPanel = KtvFullscreenPlaybackPanel(
            activity = this,
            root = binding.ktvOverlay,
            onAction = { action ->
                when (action) {
                    FullscreenPlaybackAction.PAUSE_PLAY -> togglePlayback()
                    FullscreenPlaybackAction.VOCAL -> toggleVocal()
                    FullscreenPlaybackAction.RESTART -> sendControl("restart")
                    FullscreenPlaybackAction.NEXT -> sendControl("next")
                    FullscreenPlaybackAction.VOLUME_DOWN -> changeVolume(-10)
                    FullscreenPlaybackAction.VOLUME_UP -> changeVolume(10)
                    FullscreenPlaybackAction.MUTE -> sendControl("mute", "{\"muted\":${!currentMuted}}")
                    FullscreenPlaybackAction.KIOSK -> openKioskFromFullscreen()
                    FullscreenPlaybackAction.QUEUE -> {
                        fullscreenPlaybackPanel.suspendAutoHide()
                        showQueueOverlay { fullscreenPlaybackPanel.resumeAutoHide() }
                    }
                    FullscreenPlaybackAction.HOME -> showHomePage()
                    FullscreenPlaybackAction.PITCH -> openPitchFromFullscreen()
                }
            },
            onSeekBy = { positionMs -> sendControl("seek", "{\"position_ms\":$positionMs}") },
            onFocusReturn = bottomDockController::focusVideo,
            requestExitFullscreen = bottomDockController::exitFullscreen,
        )
        KtvDashboardBackground.applyTo(binding.root)

        // 沉浸式与系统导航条防护：全屏 MV 画面保持无黑边满屏，仅浮层面板避让手势条与挖孔
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            kioskWindowInsets = intArrayOf(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            kioskOverlayBinding?.kioskRootOverlay?.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            remoteMenuBinding?.root?.setPadding(
                remoteMenuBasePadding[0] + systemBars.left,
                remoteMenuBasePadding[1] + systemBars.top,
                remoteMenuBasePadding[2] + systemBars.right,
                remoteMenuBasePadding[3] + systemBars.bottom,
            )
            insets
        }

        clock.post(clockTick)

        mediaApi = MediaApi(config)
        playbackCoordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long) = mediaApi.resolveFileSource(songId)
                override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean) =
                    mediaApi.resolvePlayback(songId, forceTranscode)
                override fun streamUrl(fileId: Long): String = mediaApi.streamUrl(fileId)
                override fun resolveStreamUrl(rawUrl: String): String = mediaApi.resolveStreamUrl(rawUrl)
            },
            desiredState = desiredPlaybackState,
            scope = lifecycleScope,
            onBeginReplacement = {
                // Invalidate the old output before waiting on the next REST
                // resolution. The coordinator owns retries; this callback only
                // performs the synchronous player-side stop.
                playbackAssetsJob?.cancel()
                playbackAssetsJob = null
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
                    playbackReplacementJob = null
                    onPlayError(
                        PlaybackErrorContext.missingSource(token.request.queueId),
                        bypassReplacementGuard = true,
                    )
                }
            },
            onPlaybackPreparing = { token, _ ->
                if (playbackCoordinator.isCurrent(token)) onToast("正在准备 MV，请稍候")
            },
            onWaitingForSource = { token, _ ->
                if (playbackCoordinator.isCurrent(token) && !token.request.forceTranscode) {
                    onToast(getString(R.string.play_waiting_network))
                }
            },
            onSourceFailure = { token, error ->
                if (playbackCoordinator.isCurrent(token)) {
                    playbackReplacementJob = null
                    val confirmedPlaybackFailure = error.code.startsWith("PLAYBACK_") ||
                        error.code.startsWith("TRANSCODE_") ||
                        error.code.startsWith("OUTPUT_") ||
                        error.code == "FILE_NOT_FOUND" || error.code == "NO_VALID_FILE"
                    if (confirmedPlaybackFailure) {
                        onToast(error.message)
                        onPlayError(
                            PlaybackErrorContext.missingSource(token.request.queueId),
                            bypassReplacementGuard = true,
                        )
                    } else {
                        val message = if (error.status == 401 || error.status == 403) {
                            "点歌服务凭据无效，请重新配置"
                        } else {
                            "歌曲详情协议异常，已停止自动播放"
                        }
                        onToast(message)
                    }
                }
            },
            onSourceExhausted = { token, _ ->
                if (playbackCoordinator.isCurrent(token)) {
                    playbackReplacementJob = null
                    onToast(getString(R.string.play_waiting_network))
                    onPlayError(
                        PlaybackSourceFailurePolicy.errorContext(token),
                        bypassReplacementGuard = true,
                    )
                }
            },
        )
        updateManager = AndroidUpdateManager(this, mediaApi) { onToast(it) }
        if (config.isConfigured) {
            updateManager.checkForUpdate(lifecycleScope)
        }
        loadQr()
        loadDisplayContent()
        scheduleDisplaySettingsTicker()
        engine = PlaybackEngine(
            context = this,
            onProgress = { pos, queueId ->
                // UI 以高频本地时钟平滑刷新，服务端进度仍保持 1s 上报频率。
                val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                if (progressReportGate.shouldReport(queueId, elapsedRealtimeMs)) {
                    socket?.sendProgress(pos, queueId, playbackSeekGate.currentSequence())
                    progressReportGate.markReported(queueId, elapsedRealtimeMs)
                }
                runOnUiThread { updateProgress(pos) }
            },
            onFinished = { queueId -> socket?.sendFinished(queueId) },
            onError = { _, context -> onPlayError(context) },
            onPlaybackResolutionRequired = { recovery ->
                val snapshot = desiredPlaybackState.forQueue(recovery.queueId)
                val songId = snapshot?.playing?.song?.id
                if (songId != null && recovery.fileId != null) {
                    val loadTicket = beginPlaybackLoad(recovery.queueId)
                    activePlaybackLoadTicket = loadTicket
                    playbackReplacementJob = playbackCoordinator.replace(
                        PlaybackReplacementRequest(
                            queueId = recovery.queueId,
                            songId = songId,
                            forceTranscode = true,
                            recoveryPositionMs = recovery.positionMs,
                            recoverySeekSequence = snapshot.seekSequence,
                            recoveryPlayWhenReady = recovery.playWhenReady,
                            stateAtFailure = snapshot.state,
                        ),
                    ).job
                }
            },
        ).also {
            it.attach(bottomDockController.playerView)
            // 遥控音量键在此 ROM 上直达系统媒体会话：监听系统音量变化上行同步服务端
            it.onExternalVolumeChange = { percent ->
                if (percent != currentVolume) sendControl("set_volume", "{\"volume\":$percent}")
            }
        }
        engine?.let { playbackEngine ->
            lifecycleScope.launch {
                playbackEngine.vocalMode.collect { mode ->
                    currentVocalMode = mode.wireValue
                    cachedQueueSnapshot?.let { snapshot ->
                        if (!snapshot.vocalMode.equals(mode.wireValue, ignoreCase = true)) {
                            val updated = snapshot.copy(vocalMode = mode.wireValue)
                            cachedQueueSnapshot = updated
                            renderBottomDock(updated)
                        }
                    }
                }
            }
        }
        presentationController = KtvPresentationController(this) { externalPlayerView ->
            if (externalPlayerView == null) {
                if (!externalDisplayActive && attachedExternalPlayerView == null) {
                    return@KtvPresentationController
                }
                externalDisplayActive = false
                bottomDockController.setExternalDisplayActive(false)
                if (::kioskController.isInitialized) kioskController.updateExternalDisplay(false)
                attachedExternalPlayerView?.let { engine?.detach(it) }
                attachedExternalPlayerView = null
                engine?.detach(bottomDockController.playerView)
                engine?.attach(bottomDockController.playerView)
                applyVideoScaleMode(currentVideoScaleMode)
                cachedQueueSnapshot?.let(::renderBottomDock)
            } else {
                externalDisplayActive = true
                bottomDockController.setExternalDisplayActive(true)
                if (bottomDockController.isFullscreen) bottomDockController.exitFullscreen()
                if (::kioskController.isInitialized) kioskController.updateExternalDisplay(true)
                attachedExternalPlayerView
                    ?.takeUnless { it === externalPlayerView }
                    ?.let { engine?.detach(it) }
                engine?.detach(bottomDockController.playerView)
                engine?.attach(externalPlayerView)
                attachedExternalPlayerView = externalPlayerView
                applyVideoScaleMode(currentVideoScaleMode)
                // Recompute from the authoritative media type as well as display state;
                // audio-only tracks must never reveal a video surface on disconnect.
                cachedQueueSnapshot?.let(::renderBottomDock)
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
        if (KioskLifecyclePolicy.shouldAutoLaunchKioskOnStart()) {
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    ensureKioskController().apply {
                        selectTab(KioskTab.DASHBOARD)
                        toggleKiosk(true)
                    }
                }
            }
        } else {
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    syncNonFullscreenFocusRails(kioskActive = false)
                    bottomDockController.focusDefaultControl(binding.btnTopSystem)
                }
            }
        }

        lifecycleScope.launch {
            kioskCoordinator.isKioskActive.collect { active ->
                syncNonFullscreenFocusRails(active)
            }
        }

        setupPersistentOverlayActions()
        if (audioPreview) {
            renderAudioPreview()
        } else {
            socket = KtvSocket(config, this).also { it.connect() }
            restoreMicrophoneMonitorIfPermitted()
        }
    }

    private fun renderAudioPreview() {
        showPlayer(audioMode = true)
        val audioUi = requireNotNull(audioOverlayBinding)
        updateArtistAvatar(null)
        audioUi.imgAudioCover.setImageDrawable(null)
        audioUi.txtAudioFallback.text = getString(R.string.app_name)
        audioUi.txtAudioTitle.text = getString(R.string.audio_preview_empty_title)
        audioUi.txtAudioArtist.text = ""
        audioUi.txtAudioNext.text = ""
        audioUi.txtAudioLyricCurrent.setLine(null, 0L)
        audioUi.txtAudioLyricNext.text = ""
        audioUi.audioProgress.progress = 0
        audioUi.txtAudioElapsed.text = getString(R.string.time_zero)
        audioUi.txtAudioDuration.text = getString(R.string.time_zero)
    }

    override fun onResume() {
        super.onResume()
        checkSessionRestart()
    }

    override fun onStart() {
        super.onStart()
        if (::fullscreenPlaybackPanel.isInitialized) fullscreenPlaybackPanel.onStart()
        if (::binding.isInitialized) {
            scheduleDisplaySettingsTicker()
        }
    }

    override fun onStop() {
        if (::fullscreenPlaybackPanel.isInitialized) fullscreenPlaybackPanel.onStop()
        removeDisplaySettingsTicker()
        super.onStop()
    }

    private fun checkSessionRestart() {
        val previous = sessionFingerprint ?: return
        val currentConfig = AppConfig(this)
        val current = DeviceSessionFingerprint(
            serverHost = currentConfig.serverHost,
            instanceId = currentConfig.serverForHost(currentConfig.serverHost)?.instanceId,
            credentialHash = DeviceSessionChangePolicy.fingerprintCredential(currentConfig.playerCredential),
        )
        if (DeviceSessionChangePolicy.requiresRestart(previous, current)) {
            recreate()
        }
    }

    override fun onDestroy() {
        dismissActiveModalDialogs()
        pitchDialog?.dismiss()
        pitchDialog = null
        clock.removeCallbacks(marqueeNotificationDismiss)
        if (::binding.isInitialized) {
            binding.txtMarquee.removeCallbacks(marqueeNotificationDismiss)
            binding.txtMarquee.animate().cancel()
        }
        invalidatePlaybackLoad()
        socket?.close()
        presentationController?.stop()
        presentationController = null
        attachedExternalPlayerView?.let { engine?.detach(it) }
        attachedExternalPlayerView = null
        clock.removeCallbacks(clockTick)
        clock.removeCallbacks(progressHide)
        if (::binding.isInitialized) {
            binding.root.removeCallbacks(displaySettingsTicker)
        }
        socket = null
        if (::binding.isInitialized && ::bottomDockController.isInitialized) {
            if (::fullscreenPlaybackPanel.isInitialized) fullscreenPlaybackPanel.destroy()
            engine?.detach(bottomDockController.playerView)
            bottomDockController.destroy()
        }
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
        } else {
            kioskCoordinator.destroy()
        }
        super.onDestroy()
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (consumeActiveModalDialogBack(event)) return true
        if (remoteMenuBinding?.root?.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) resetMenuTimer()
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    remoteMenuBinding?.root?.visibility = View.GONE
                    val returnTarget = remoteMenuReturnFocus
                    remoteMenuReturnFocus = null
                    val restored = returnTarget
                        ?.takeIf { it.isAttachedToWindow && it.isShown && it.isFocusable }
                        ?.requestRemoteFocus() == true
                    if (!restored) {
                        bottomDockController.focusDefaultControl(binding.btnTopSettings)
                    }
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (::fullscreenPlaybackPanel.isInitialized && fullscreenPlaybackPanel.handleKeyEvent(event)) {
            return true
        }
        if (bottomDockController.isFullscreen && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { changeVolume(10); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { changeVolume(-10); return true }
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> {
                    sendControl("mute", "{\"muted\":${!currentMuted}}")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { sendControl("next"); return true }
            }
        }
        if (::kioskController.isInitialized && kioskController.dispatchKeyEvent(event)) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (::kioskController.isInitialized && focusController.shouldInterceptMenu()) {
                return true
            }
            ensureKioskController().toggleKiosk(true)
            return true
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
            if (remoteMenuBinding?.root?.visibility != View.VISIBLE && !kioskCoordinator.isKioskActive.value) {
                when (
                    KioskLifecyclePolicy.resolveNonKioskKeyAction(
                        keyCode = event.keyCode,
                        focusedViewIsInteractive = isInteractiveFocusedView(currentFocus),
                    )
                ) {
                    NonKioskKeyAction.OPEN_KIOSK -> {
                        ensureKioskController().resumeKiosk()
                        return true
                    }
                    NonKioskKeyAction.TOGGLE_PLAYBACK -> {
                        togglePlayback()
                        return true
                    }
                    NonKioskKeyAction.NEXT_SONG -> {
                        sendControl("next")
                        return true
                    }
                    NonKioskKeyAction.PASS_TO_FOCUS -> Unit
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP &&
                activeModalDialogs.none { it.isShowing } &&
                !bottomDockController.isFullscreen
            ) {
                val hasPlaying = cachedQueueSnapshot?.playing != null || cachedQueueSnapshot?.state == "playing"
                when (KioskLifecyclePolicy.resolveDashboardBackAction(hasPlaying, tvSettingsStore.snapshot().exitOnBackEnabled)) {
                    KioskBackAction.PROMPT_EXIT_WITH_FULLSCREEN,
                    KioskBackAction.PROMPT_EXIT_ONLY -> showExitConfirmDialog(hasPlaying)
                    KioskBackAction.RETURN_TO_FULLSCREEN_MV -> {
                        if (bottomDockController.isVideoVisible && !bottomDockController.externalDisplayActive) {
                            bottomDockController.enterFullscreen()
                        }
                    }
                    KioskBackAction.STAY_ON_DASHBOARD -> Unit
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInteractiveFocusedView(view: View?): Boolean {
        if (view == null || !view.isShown) return false
        if (view === binding.root ||
            view === binding.sharedPlayerContainer ||
            view === binding.playerView
        ) {
            return false
        }
        return view.isClickable
    }

    private fun syncNonFullscreenFocusRails(kioskActive: Boolean = kioskCoordinator.isKioskActive.value) {
        if (!::binding.isInitialized || !::bottomDockController.isInitialized) return
        val topButtons = listOf(
            binding.btnTopSystem,
            binding.btnTopMobileQr,
            binding.btnTopSettings,
            binding.btnTopSearch,
            binding.btnTopLibraryScan,
            binding.btnTopMixer,
        )
        if (kioskActive) {
            topButtons.forEach { it.nextFocusDownId = View.NO_ID }
            if (::kioskController.isInitialized) {
                val isDashboard = kioskOverlayBinding?.kioskDashboardView?.visibility == View.VISIBLE
                bottomDockController.updateFocusUpMode(
                    if (isDashboard) KtvBottomDockController.DockFocusUpMode.KIOSK_DASHBOARD
                    else KtvBottomDockController.DockFocusUpMode.KIOSK_BROWSER,
                )
            }
            return
        }
        val dockTargetId = bottomDockController.defaultDownTargetId()
        topButtons.forEach { it.nextFocusDownId = dockTargetId }
        val visibleTopAnchorId = topButtons.firstOrNull { it.visibility == View.VISIBLE && it.isFocusable }?.id ?: View.NO_ID
        bottomDockController.updateFocusUpMode(
            KtvBottomDockController.DockFocusUpMode.HEADER_BAR,
            headerAnchorId = visibleTopAnchorId,
        )
    }

    // ---- 音量 OSD ----

    private val volumeHide = Runnable { binding.volumeOsd.visibility = View.GONE }

    /** 任何来源的音量变化都调用：顶部显示 2.5s 当前音量与满量程进度。 */
    private fun showVolumeOsd(volume: Int, muted: Boolean) {
        binding.txtVolume.text = if (muted) "已静音" else "音量 $volume"
        binding.volumeBar.progress = if (muted) 0 else volume
        binding.volumeOsd.visibility = View.VISIBLE
        binding.volumeOsd.bringToFront()
        binding.volumeOsd.removeCallbacks(volumeHide)
        binding.volumeOsd.postDelayed(volumeHide, 2_500L)
    }

    // ---- 原伴唱 HUD ----

    private val vocalOsdHide = Runnable { binding.vocalOsd.visibility = View.GONE }

    /** 切换原伴唱时屏幕中央浮现 1.5s 金色 HUD。 */
    private fun showVocalOsd(mode: String) {
        binding.txtVocalOsd.text = VocalTogglePolicy.resolveOsdText(mode)
        binding.vocalOsd.visibility = View.VISIBLE
        binding.vocalOsd.bringToFront()
        binding.vocalOsd.removeCallbacks(vocalOsdHide)
        binding.vocalOsd.postDelayed(vocalOsdHide, 1_500L)
    }

    private fun ensureAudioOverlayBinding(): ViewAudioOverlayBinding {
        audioOverlayBinding?.let { return it }
        val audioUi = ViewAudioOverlayBinding.bind(binding.audioOverlayStub.inflate())
        KtvDashboardBackground.applyTo(audioUi.root)
        audioOverlayBinding = audioUi
        cachedKioskQrBitmap?.let(audioUi.imgAudioMiniQr::setImageBitmap)
        audioMiniQrVisibility?.let { audioUi.imgAudioMiniQr.visibility = it }
        audioMiniQrLabelVisibility?.let { audioUi.txtAudioMiniQrHint.visibility = it }
        return audioUi
    }

    private fun ensureRemoteMenuBinding(): ViewRemoteMenuBinding {
        remoteMenuBinding?.let { return it }
        val menu = ViewRemoteMenuBinding.bind(binding.remoteMenuStub.inflate())
        remoteMenuBinding = menu
        remoteMenuBasePadding = intArrayOf(
            menu.root.paddingLeft,
            menu.root.paddingTop,
            menu.root.paddingRight,
            menu.root.paddingBottom,
        )
        menu.root.setPadding(
            remoteMenuBasePadding[0] + kioskWindowInsets[0],
            remoteMenuBasePadding[1] + kioskWindowInsets[1],
            remoteMenuBasePadding[2] + kioskWindowInsets[2],
            remoteMenuBasePadding[3] + kioskWindowInsets[3],
        )
        setupRemoteMenu(menu)
        return menu
    }

    private fun setupRemoteMenu(menu: ViewRemoteMenuBinding) {
        menu.remotePlay.setOnClickListener { togglePlayback() }
        menu.remoteNext.setOnClickListener { sendControl("next") }
        menu.remoteRestart.setOnClickListener { sendControl("restart") }
        menu.remoteVocal.setOnClickListener { toggleVocal() }
        menu.remoteVolUp.setOnClickListener { changeVolume(10) }
        menu.remoteVolDown.setOnClickListener { changeVolume(-10) }
        menu.remoteMute.setOnClickListener { sendControl("mute", "{\"muted\":${!currentMuted}}") }
        menu.remoteQueue.setOnClickListener {
            menu.root.visibility = View.GONE
            ensureKioskController().openQueueDrawer()
        }
        menu.remoteOrder.setOnClickListener {
            menu.root.visibility = View.GONE
            ensureKioskController().toggleKiosk(true)
        }
        menu.remoteOrder.visibility = View.VISIBLE
        menu.remoteMicrophone.setOnClickListener { toggleMicrophoneMonitor() }
        updateMicrophoneButton()
        menu.remoteCheer.setOnClickListener { onEffect("cheer") }
        menu.remoteSettings.setOnClickListener {
            menu.root.visibility = View.GONE
            settingsLauncher.launch(Intent(this, SetupActivity::class.java).apply {
                putExtra(SetupActivity.EXTRA_FORCE_SETUP, true)
                putExtra(SetupActivity.EXTRA_RETURN_TO_CALLER, true)
            })
        }
    }

    private fun setupPersistentOverlayActions() {
        // 顶栏 6 大快捷图标事件
        binding.btnTopSystem.setOnClickListener { showSystemInfoDialog() }
        binding.btnTopMobileQr.setOnClickListener { showQrCode() }
        binding.btnTopSettings.setOnClickListener { showKtvSettingsDialog() }
        binding.btnTopSearch.visibility = View.VISIBLE
        binding.btnTopSearch.contentDescription = "全局搜索"
        binding.btnTopSearch.setOnClickListener {
            ensureKioskController().openGlobalSearch(binding.btnTopSearch)
        }
        binding.btnTopLibraryScan.contentDescription = "请求服务端扫描曲库"
        binding.btnTopLibraryScan.setOnClickListener { showLibraryScanConfirmDialog() }
        binding.btnTopMixer.contentDescription = "调音与麦克风"
        binding.btnTopMixer.setOnClickListener { showMixerPanel() }
    }

    private fun showKtvSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ktv_settings, null)
        val metrics = KtvSettingsLayoutPolicy.forScreenHeight(resources.displayMetrics.heightPixels)
        val settingsBackdrop = dialogView.findViewById<View>(R.id.settingsStageBackdrop)
        KtvDashboardBackground.applyTo(settingsBackdrop)
        val settingsGrid = dialogView.findViewById<View>(R.id.settingsGrid)
        settingsGrid.translationY = metrics.translationYPx.toFloat()
        settingsGrid.setPadding(metrics.horizontalInsetPx, 0, metrics.horizontalInsetPx, 0)
        dialogView.findViewById<View>(R.id.settingsColumnGap).apply {
            layoutParams = layoutParams.apply { width = metrics.columnGapPx }
        }
        val returnFocus = binding.btnTopSettings
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(dialogView)
            setCancelable(true)
        }
        dialog.setOnDismissListener {
            untrackModalDialog(dialog)
            if (::kioskController.isInitialized) {
                kioskController.resumeDashboardPhotoCarousel(
                    KtvDashboardPhotoCarousel.PauseReason.SETTINGS_DIALOG,
                )
            }
            restoreFocus(returnFocus)
        }
        val entries = settingsEntries()
        lateinit var rows: Map<String, KtvSettingsRowView>
        rows = KtvSettingsRowFactory(this).populate(
            leftColumn = dialogView.findViewById(R.id.settingColumnLeft),
            rightColumn = dialogView.findViewById(R.id.settingColumnRight),
            entries = entries,
        ) { entry ->
            val row = rows[entry.key] ?: return@populate
            when (entry.action) {
                KtvSettingsAction.OPEN_LIBRARY_SCAN -> showLibraryScanConfirmDialog(row)
                KtvSettingsAction.OPEN_MEDIA_FEEDBACK -> showMediaFeedbackDialog(row)
                KtvSettingsAction.TOGGLE_LOCAL_PREFERENCE -> {
                    if (!entry.enabled) {
                        showInformationalDialog(entry.title, entry.description, row)
                        return@populate
                    }
                    if (!tvSettingsStore.set(entry.key, entry.checked)) {
                        onToast("设置保存失败")
                        return@populate
                    }
                    row.bind(settingsEntries().first { it.key == entry.key })
                    if (entry.key == KtvTvSettingsStore.KEY_AUTO_FULLSCREEN) {
                        kioskCoordinator.resetIdleTimer()
                    }
                    if (entry.key == KtvTvSettingsStore.KEY_GLOBAL_SEARCH && ::kioskController.isInitialized) {
                        kioskController.refreshGlobalSearchPreference()
                    }
                }
                KtvSettingsAction.EXPLAIN -> {
                    if (entry.key == "download_server") {
                        showDownloadServerDialog(entry.title, entry.description, row)
                    } else {
                        showInformationalDialog(entry.title, entry.description, row)
                    }
                }
            }
        }
        trackModalDialog(dialog)
        returnFocus.clearFocus()
        if (::kioskController.isInitialized) {
            kioskController.pauseDashboardPhotoCarousel(
                KtvDashboardPhotoCarousel.PauseReason.SETTINGS_DIALOG,
            )
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
        rows["download_path"]?.let { firstRow ->
            firstRow.post { firstRow.requestRemoteFocus() }
        }
    }

    private fun settingsEntries(): List<KtvSettingsEntry> = tvSettingsStore.snapshot().let { settings ->
        KtvSettingsCatalog.entries(
            autoFullscreenEnabled = settings.autoFullscreenEnabled,
            globalSearchEnabled = settings.globalSearchEnabled,
            exitOnBackEnabled = settings.exitOnBackEnabled,
            startupSyncEnabled = settings.startupSyncEnabled,
        )
    }

    private fun showSystemInfoDialog() {
        val server = config.serverHost?.takeIf { it.isNotBlank() } ?: "未配置"
        val serviceStatus = if (::kioskController.isInitialized) {
            kioskController.serviceStatusSummary()
        } else {
            "点歌控制器尚未初始化"
        }
        val info = "Home KTV 系统状态\n\n" +
                "服务器地址: " + server + "\n" +
                "服务状态: " + serviceStatus + "\n" +
                "客户端版本: " + BuildConfig.VERSION_NAME + "\n" +
                "播放器: AndroidX Media3\n" +
                "播放控制长连接: " + (if (playerConnected) "已连接" else "未连接")
        showTrackedInfoModal(
            title = "系统信息",
            message = info,
            returnFocus = binding.btnTopSystem,
            secondaryActionLabel = getString(R.string.server_mode_label),
            onSecondaryAction = ::launchServiceAndDeviceSettings,
        )
    }

    private fun launchServiceAndDeviceSettings() {
        settingsLauncher.launch(Intent(this, SetupActivity::class.java).apply {
            putExtra(SetupActivity.EXTRA_FORCE_SETUP, true)
            putExtra(SetupActivity.EXTRA_RETURN_TO_CALLER, true)
        })
    }

    private fun showInformationalDialog(title: String, message: String, returnFocus: View) {
        showTrackedInfoModal(
            title = title,
            message = message,
            returnFocus = returnFocus,
        )
    }

    private fun showDownloadServerDialog(title: String, message: String, returnFocus: View) {
        showTrackedInfoModal(
            title = title,
            message = message,
            returnFocus = returnFocus,
            secondaryActionLabel = "查看系统信息",
            onSecondaryAction = ::showSystemInfoDialog,
        )
    }

    private fun showTrackedInfoModal(
        title: String,
        message: String,
        returnFocus: View,
        secondaryActionLabel: String? = null,
        onSecondaryAction: (() -> Unit)? = null,
    ) {
        val dialog = KtvSystemInfoDialog(
            context = this,
            title = title,
            message = message,
            secondaryActionLabel = secondaryActionLabel,
        )
        dialog.setOnDismissListener {
            untrackModalDialog(dialog)
            if (dialog.wasSecondaryActionTriggered) {
                onSecondaryAction?.invoke()
            } else {
                restoreFocus(returnFocus)
            }
        }
        trackModalDialog(dialog)
        dialog.show()
    }

    private fun showLibraryScanConfirmDialog(returnFocus: View = binding.btnTopLibraryScan) {
        val content = layoutInflater.inflate(R.layout.dialog_library_scan_confirm, null)
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }
        dialog.setOnDismissListener {
            untrackModalDialog(dialog)
            restoreFocus(returnFocus)
        }
        trackModalDialog(dialog)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(350), dp(195))
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.58f)
        }
        val cancel = content.findViewById<View>(android.R.id.button2)
        val confirm = content.findViewById<View>(android.R.id.button1)
        var submitted = false
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            if (submitted || libraryScanRequestInFlight) return@setOnClickListener
            submitted = true
            cancel.isEnabled = false
            confirm.isEnabled = false
            dialog.dismiss()
            triggerLibraryScan()
        }
        cancel.post { cancel.requestRemoteFocus() }
    }

    private fun showMediaFeedbackDialog(returnFocus: View) {
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = android.widget.EditText(this).apply {
            hint = "输入媒体名称或关键词"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val status = android.widget.TextView(this).apply {
            setTextColor(Color.rgb(230, 83, 102))
            textSize = 12f
            visibility = View.GONE
        }
        content.addView(input, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        content.addView(status, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val dialog = AlertDialog.Builder(this, R.style.Theme_HomeKtvTv_UpdateDialog)
            .setTitle("媒体反馈")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("提交", null)
            .create()
        dialog.setOnDismissListener {
            untrackModalDialog(dialog)
            restoreFocus(returnFocus)
        }
        trackModalDialog(dialog)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val keyword = input.text.toString().trim()
            if (keyword.isBlank()) {
                status.text = "请输入媒体名称"
                status.visibility = View.VISIBLE
                input.requestRemoteFocus()
                return@setOnClickListener
            }
            val submitButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submitButton.isEnabled = false
            status.text = "正在提交"
            status.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.IO) {
                val transport = com.homektv.tv.net.KtvHttpTransport(config)
                val result = try {
                    com.homektv.tv.net.WishApi(transport, config.userToken).add(keyword)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } finally {
                    transport.close()
                }
                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.homektv.tv.net.KtvApiResult.Success -> {
                            onToast("已提交点歌心愿")
                            dialog.dismiss()
                        }
                        is com.homektv.tv.net.KtvApiResult.Failure -> {
                            status.text = result.error.message.ifBlank { "提交失败，请重试" }
                            submitButton.isEnabled = true
                        }
                        null -> {
                            status.text = "连接服务失败，请检查网络后重试"
                            submitButton.isEnabled = true
                        }
                    }
                }
            }
        }
        input.post { input.requestRemoteFocus() }
    }

    private fun restoreFocus(target: View) {
        target.post {
            if (target.isAttachedToWindow && target.isShown && !target.isInTouchMode) {
                target.requestRemoteFocus()
            }
        }
    }

    private fun suspendBackgroundFocusForModal() {
        if (backgroundFocusSuspended || !::binding.isInitialized) return
        previousRootDescendantFocusability = binding.root.descendantFocusability
        binding.root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        currentFocus?.clearFocus()
        binding.root.clearFocus()
        backgroundFocusSuspended = true
    }

    private fun restoreBackgroundFocusAfterModal() {
        if (!backgroundFocusSuspended || !::binding.isInitialized) return
        binding.root.descendantFocusability = previousRootDescendantFocusability
        backgroundFocusSuspended = false
    }

    private fun trackModalDialog(dialog: Dialog) {
        activeModalDialogs.remove(dialog)
        activeModalDialogs.add(dialog)
        kioskCoordinator.setModalActive(true)
        suspendBackgroundFocusForModal()
    }

    private fun untrackModalDialog(dialog: Dialog) {
        activeModalDialogs.remove(dialog)
        val hasActiveModal = activeModalDialogs.any { it.isShowing }
        kioskCoordinator.setModalActive(hasActiveModal)
        if (!hasActiveModal) restoreBackgroundFocusAfterModal()
    }

    internal fun showTrackedAlertDialog(dialog: AlertDialog, onDismiss: (() -> Unit)? = null) {
        dialog.setOnDismissListener {
            untrackModalDialog(dialog)
            onDismiss?.invoke()
        }
        trackModalDialog(dialog)
        dialog.show()
    }

    private fun showExitConfirmDialog(hasPlaying: Boolean) {
        if (isFinishing || isDestroyed) return
        if (exitConfirmDialog?.isShowing == true) return
        val builder = AlertDialog.Builder(this, R.style.Theme_HomeKtvTv_UpdateDialog)
            .setTitle("退出应用")
            .setMessage(if (hasPlaying) "当前正在播放歌曲，确定要退出家庭 KTV 吗？" else "确定要退出家庭 KTV 吗？")
            .setPositiveButton("退出应用") { _, _ ->
                confirmExitApplication()
            }
        if (hasPlaying) {
            builder.setNegativeButton("返回全屏播放") { _, _ ->
                bottomDockController.enterFullscreen()
            }
            builder.setNeutralButton("取消", null)
        } else {
            builder.setNegativeButton("取消", null)
        }
        val dialog = builder.create()
        exitConfirmDialog = dialog
        showTrackedAlertDialog(dialog) {
            if (exitConfirmDialog === dialog) exitConfirmDialog = null
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestRemoteFocus()
    }

    private fun confirmExitApplication() {
        dismissActiveModalDialogs()
        runCatching { engine?.stop() }
        finish()
    }

    private var exitConfirmDialog: AlertDialog? = null

    private fun dismissActiveModalDialogs() {
        val dialogs = activeModalDialogs.toList()
        activeModalDialogs.clear()
        dialogs.forEach { dialog -> runCatching { dialog.dismiss() } }
        exitConfirmDialog = null
        topBarQrDialog = null
        pitchDialog = null
        kioskCoordinator.setModalActive(false)
        restoreBackgroundFocusAfterModal()
    }

    private fun consumeActiveModalDialogBack(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        val activeDialog = activeModalDialogs.lastOrNull { it.isShowing } ?: return false
        if (event.action == KeyEvent.ACTION_UP) activeDialog.dismiss()
        return true
    }

    private fun triggerLibraryScan() {
        if (libraryScanRequestInFlight) return
        libraryScanRequestInFlight = true
        onToast("正在请求服务端扫描曲库…")
        lifecycleScope.launch(Dispatchers.IO) {
            val transport = com.homektv.tv.net.KtvHttpTransport(config)
            try {
                when (val result = com.homektv.tv.net.LibraryApi(transport).requestScan()) {
                    is com.homektv.tv.net.KtvApiResult.Success -> runOnUiThread {
                        onToast(LibraryScanFeedbackPolicy.messageFor(result.value))
                    }
                    is com.homektv.tv.net.KtvApiResult.Failure -> runOnUiThread {
                        onToast(LibraryScanFeedbackPolicy.failureMessage(result.error.message))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                runOnUiThread { onToast(LibraryScanFeedbackPolicy.connectionFailureMessage) }
            } finally {
                transport.close()
                runOnUiThread { libraryScanRequestInFlight = false }
            }
        }
    }

    private fun maybeStartConfiguredStartupScan() {
        if (startupScanAttemptedInProcess) return
        startupScanAttemptedInProcess = true
        if (!tvSettingsStore.snapshot().startupSyncEnabled) return
        if (libraryScanRequestInFlight) return
        libraryScanRequestInFlight = true
        lifecycleScope.launch(Dispatchers.IO) {
            val transport = com.homektv.tv.net.KtvHttpTransport(config)
            var feedback: String? = null
            try {
                val api = com.homektv.tv.net.LibraryApi(transport)
                val status = when (val result = api.status()) {
                    is com.homektv.tv.net.KtvApiResult.Success -> result.value
                    is com.homektv.tv.net.KtvApiResult.Failure -> null
                }
                if (StartupScanPolicy.shouldRequest(
                        enabled = true,
                        connected = playerConnected,
                        status = status,
                    )
                ) {
                    feedback = when (val result = api.requestScan()) {
                        is com.homektv.tv.net.KtvApiResult.Success -> LibraryScanFeedbackPolicy.messageFor(result.value)
                        is com.homektv.tv.net.KtvApiResult.Failure ->
                            LibraryScanFeedbackPolicy.failureMessage(result.error.message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback = LibraryScanFeedbackPolicy.connectionFailureMessage
            } finally {
                transport.close()
                withContext(Dispatchers.Main) {
                    libraryScanRequestInFlight = false
                    feedback?.let { if (!isFinishing && !isDestroyed) onToast(it) }
                }
            }
        }
    }

    private fun resetMenuTimer() {
        remoteMenuBinding?.root?.let { menu ->
            menu.removeCallbacks(menuHide)
            menu.postDelayed(menuHide, 10_000L)
        }
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
        remoteMenuBinding?.remoteMicrophone?.text = if (microphoneActive) "麦克风：开" else "麦克风：关"
    }

    private var currentVideoScaleMode: String = "zoom"

    private fun applyVideoScaleMode(mode: String) {
        currentVideoScaleMode = mode
        val resizeMode = when (mode) {
            "fit" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        bottomDockController.playerView.resizeMode = resizeMode
        attachedExternalPlayerView?.resizeMode = resizeMode
    }

    private var currentVolume = 100
    private var currentMuted = false
    private var currentVocalMode = "accompaniment"
    private var currentAudioLayout = AudioLayout.normalStereo()

    private fun togglePlayback() {
        sendControl(if (currentPlaybackState == "playing") "pause" else "play")
    }

    /** 原唱 ↔ 伴唱切换（遥控菜单按钮与方向下键共用）。 */
    private fun toggleVocal() {
        val nextMode = VocalTogglePolicy()
            .getNextVocalMode(VocalMode.fromWire(currentVocalMode))
            .wireValue
        currentVocalMode = nextMode
        engine?.setVocalMode(nextMode, accompanimentTrackIndex, audioTrackCount, currentAudioLayout)
        cachedQueueSnapshot?.copy(vocalMode = nextMode)?.let(::renderBottomDock)
        showVocalOsd(nextMode)
        sendControl("set_vocal", "{\"mode\":\"$nextMode\"}")
    }

    private fun changeVolume(delta: Int) {
        val volume = (currentVolume + delta).coerceIn(0, 100)
        currentVolume = volume
        engine?.applyVolume(volume, currentMuted)
        showVolumeOsd(volume, currentMuted)
        sendControl("set_volume", "{\"volume\":$volume}")
    }

    private fun showQueueOverlay(onDismiss: (() -> Unit)? = null) {
        remoteMenuBinding?.root?.visibility = View.GONE
        ensureKioskController().openQueueDrawer(onDismiss)
    }

    private fun openKioskFromFullscreen() {
        if (bottomDockController.isFullscreen) bottomDockController.exitFullscreen()
        ensureKioskController().resumeKiosk()
    }

    private fun showHomePage() {
        if (bottomDockController.isFullscreen) bottomDockController.exitFullscreen()
        binding.ktvOverlay.visibility = View.GONE
        audioOverlayBinding?.root?.visibility = View.GONE
        binding.pageContainer.visibility = View.VISIBLE
        ensureKioskController().openDashboardHome()
    }

    private fun openHistoryTab() {
        ensureKioskController().openHistoryFromShortcut()
    }

    private fun showMixerPanel() {
        remoteMenuReturnFocus = currentFocus
        val menu = ensureRemoteMenuBinding()
        updateMicrophoneButton()
        menu.root.visibility = View.VISIBLE
        menu.root.bringToFront()
        menu.remotePlay.requestRemoteFocus()
        resetMenuTimer()
    }

    private fun showQrCode() {
        val returnFocus = currentFocus ?: binding.btnTopMobileQr
        val dialog = KtvQrDialog(
            context = this,
            portalUrl = KtvQrPolicy.formatPortalUrl(config.serverHost),
            qrBitmap = cachedKioskQrBitmap,
            coordinator = kioskCoordinator.takeIf { kioskCoordinator.isKioskActive.value },
        )
        dialog.onDismissQr = {
            if (topBarQrDialog === dialog) topBarQrDialog = null
            untrackModalDialog(dialog)
            restoreFocus(returnFocus)
        }
        dialog.onRetryQr = {
            dialog.showLoading()
            loadQr()
        }
        topBarQrDialog = dialog
        trackModalDialog(dialog)
        dialog.show()
        if (cachedKioskQrBitmap == null) {
            if (config.serverHost.isNullOrBlank()) {
                dialog.showLoadError("尚未配置 Home KTV 服务地址，请先完成服务连接设置。", retryable = false)
            } else {
                dialog.showLoading()
                loadQr()
            }
        }
    }

    private fun showDownloadsDrawer() {
        ensureKioskController().openQueueDrawer(initialTab = KtvQueueDrawerTab.DOWNLOADS)
    }

    private fun openPitchFromFullscreen() {
        val returnFocus = currentFocus ?: binding.root.findViewById(R.id.btnFullscreenPitch)
        val activeEngine = engine
        if (activeEngine == null) {
            showPitchUnavailable(returnFocus, PlaybackPitchStatus.NO_MEDIA)
            return
        }
        val currentResult = activeEngine.setPitchSemitones(activeEngine.pitchSemitones)
        if (!currentResult.applied) {
            showPitchUnavailable(returnFocus, currentResult.status)
            return
        }
        showPitchDialog(returnFocus, activeEngine, currentResult.semitones)
    }

    private fun onPitchStep(delta: Int) {
        val returnFocus = currentFocus ?: if (delta < 0) {
            binding.root.findViewById(R.id.btnDockToneDown)
        } else {
            binding.root.findViewById(R.id.btnDockToneUp)
        }
        val activeEngine = engine
        if (activeEngine == null) {
            showPitchUnavailable(returnFocus, PlaybackPitchStatus.NO_MEDIA)
            return
        }
        val result = activeEngine.setPitchSemitones(activeEngine.pitchSemitones + delta)
        if (!result.applied) {
            showPitchUnavailable(returnFocus, result.status)
            return
        }
        showPitchDialog(returnFocus, activeEngine, result.semitones)
    }

    private fun showPitchDialog(
        returnFocus: View,
        activeEngine: PlaybackEngine,
        initialSemitones: Int,
    ) {
        showPitchModal(
            returnFocus = returnFocus,
            dialog = KtvPitchDialog(
                context = this,
                initialSemitones = initialSemitones,
                setPitch = activeEngine::setPitchSemitones,
            ),
        )
    }

    private fun showPitchUnavailable(returnFocus: View, status: PlaybackPitchStatus) {
        showPitchModal(
            returnFocus = returnFocus,
            dialog = KtvPitchDialog(
                context = this,
                initialSemitones = 0,
                setPitch = { PlaybackPitchResult(status = status, semitones = 0, pitch = 1.0f) },
                unavailableStatus = status,
            ),
        )
    }

    private fun showPitchModal(returnFocus: View, dialog: KtvPitchDialog) {
        pitchDialog?.takeIf { it.isShowing }?.dismiss()
        pitchDialog = dialog
        if (bottomDockController.isFullscreen) fullscreenPlaybackPanel.suspendAutoHide()
        dialog.setOnDismissListener {
            if (pitchDialog === dialog) pitchDialog = null
            untrackModalDialog(dialog)
            if (bottomDockController.isFullscreen) fullscreenPlaybackPanel.resumeAutoHide()
            restoreFocus(returnFocus)
        }
        trackModalDialog(dialog)
        dialog.show()
    }

    private fun onDockFullscreenChanged(fullscreen: Boolean) {
        if (!::binding.isInitialized) return
        kioskCoordinator.setFullscreenPlaybackActive(fullscreen)
        if (fullscreen) {
            fullscreenKioskActive = kioskCoordinator.isKioskActive.value
            binding.headerBar.visibility = View.GONE
            binding.pageContainer.visibility = View.GONE
            bottomDockController.root.visibility = View.GONE
            remoteMenuBinding?.root?.visibility = View.GONE
            kioskOverlayBinding?.kioskRootOverlay?.visibility = View.GONE
            binding.playerInfoPanel.visibility = View.GONE
            binding.nextPanel.visibility = View.GONE
            binding.playProgress.visibility = View.GONE
            binding.txtElapsed.visibility = View.GONE
            binding.txtVocalMode.visibility = View.GONE
            binding.txtDuration.visibility = View.GONE
            binding.ktvOverlay.visibility = View.VISIBLE
            binding.sharedPlayerContainer.bringToFront()
            binding.ktvOverlay.bringToFront()
            fullscreenPlaybackPanel.setFullscreen(true)
            renderFullscreenPlaybackPanel()
        } else {
            fullscreenPlaybackPanel.setFullscreen(false)
            hidePlaybackProgress()
            binding.ktvOverlay.visibility = View.GONE
            binding.headerBar.visibility = View.VISIBLE
            binding.pageContainer.visibility = View.VISIBLE
            bottomDockController.root.visibility = View.VISIBLE
            if (fullscreenKioskActive && kioskCoordinator.isKioskActive.value) {
                kioskOverlayBinding?.kioskRootOverlay?.visibility = View.VISIBLE
            }
            fullscreenKioskActive = false
            syncNonFullscreenFocusRails()
            if (!kioskCoordinator.isKioskActive.value) {
                bottomDockController.focusDefaultControl(binding.btnTopSystem)
            }
        }
    }

    private fun onKioskIdleTimeout(): Boolean {
        if (!::binding.isInitialized || !::bottomDockController.isInitialized) return false
        if (!kioskCoordinator.isKioskActive.value) return false
        val shouldEnterFullscreen = KioskIdleTimeoutPolicy.shouldEnterFullscreen(
            autoFullscreenEnabled = tvSettingsStore.snapshot().autoFullscreenEnabled,
            hasVisibleVideo = bottomDockController.isVideoVisible,
            externalDisplayActive = bottomDockController.externalDisplayActive,
            isPlaying = currentPlaybackState == "playing",
            hasActiveModal = kioskCoordinator.isIdleTimeoutSuspended() ||
                activeModalDialogs.any { it.isShowing } ||
                remoteMenuBinding?.root?.visibility == View.VISIBLE,
        )
        if (shouldEnterFullscreen) bottomDockController.enterFullscreen()

        // A timeout without an eligible fullscreen transition is handled by keeping the
        // ordering dashboard visible; deactivating Kiosk here leaves TV mode on an empty page.
        return true
    }

    private fun sendControl(action: String, params: String = "{}") {
        if (bottomDockController.isFullscreen) fullscreenPlaybackPanel.resumeAutoHide()
        else showPlaybackProgress()
        lifecycleScope.launch { if (!mediaApi.control(action, params)) onToast("操作失败") }
    }

    // ---- KtvSocket.Listener ----

    override fun onConnectionChanged(connected: Boolean) {
        playerConnected = connected
        if (!connected) lastSnapshotRevision = 0L
        playerConnectionStatus = PlayerConnectionStatusPolicy.onConnectionChanged(connected, playerRoleActive)
        renderPlayerConnectionStatus()
        if (!connected) cachedQueueSnapshot?.let { renderBottomDock(it, queueSnapshotFresh = false) }
        if (connected) maybeStartConfiguredStartupScan()
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
        binding.txtStatus.visibility = View.GONE
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
        if (marqueeNotificationController.enqueueSnapshotChange(event, cachedQueueSnapshot, snapshot) > 0) {
            showNextMarqueeNotification()
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
        currentPlaybackState = snapshot.state
        val lyricsPlaying = snapshot.state == "playing"
        binding.txtLyricPrevious.updatePlayback(engine?.currentPositionMs ?: 0L, lyricsPlaying)
        audioOverlayBinding?.txtAudioLyricCurrent?.updatePlayback(engine?.currentPositionMs ?: 0L, lyricsPlaying)
        hasCurrentSong = snapshot.playing != null
        cachedQueueSnapshot = snapshot
        renderFullscreenPlaybackPanel(snapshot.positionMs)
        binding.txtPhones.visibility = View.GONE
        binding.txtPhones.text =
            getString(R.string.status_phones, snapshot.connectedPhones.toInt())
        renderBottomDock(snapshot)
        applyPlayback(snapshot)
        if (event == VOCAL_CHANGED_EVENT) refreshVocalTrackMapping(snapshot)
    }

    private fun renderBottomDock(snapshot: QueueSnapshot, queueSnapshotFresh: Boolean = true) {
        val song = snapshot.playing?.song
        val isVideo = song != null && !song.mediaType.equals("AUDIO", ignoreCase = true)
        val queueCount = SongQueueProjection.waitingCount(snapshot)
        val trackCount = if (snapshot.audioLayout.layout.equals("DUAL_TRACK", ignoreCase = true)) {
            maxOf(audioTrackCount, 2)
        } else {
            audioTrackCount
        }
        val nowPlayingLabel = song?.let { current ->
            listOfNotNull(current.title, current.artist?.takeIf(String::isNotBlank)).joinToString(" · ")
        } ?: "等待点歌"
        val nowPlayingPrefix = if (snapshot.playing?.autoPlay == true) "随机公播" else "正在播放"
        baselineMarqueeText = when {
            song != null -> "$nowPlayingPrefix：$nowPlayingLabel"
            queueCount > 0 -> "待播歌曲 $queueCount 首 · 手机扫码或使用遥控器点歌"
            else -> "手机扫码点歌 · 欢迎使用 Home KTV"
        }
        if (activeMarqueeNotification == null) binding.txtMarquee.text = baselineMarqueeText
        bottomDockController.render(
            BottomDockUiModel(
                queueCount = queueCount,
                isPlaying = snapshot.state == "playing",
                vocalMode = snapshot.vocalMode,
                vocalSwitchEnabled = supportsVocalSwitch(snapshot.audioLayout, trackCount),
                videoVisible = isVideo && snapshot.state != "idle" && !externalDisplayActive,
                nowPlayingLabel = nowPlayingLabel,
                queueCountKnown = true,
                queueSnapshotFresh = queueSnapshotFresh,
            ),
        )
    }

    private fun showNextMarqueeNotification() {
        if (activeMarqueeNotification != null || !::binding.isInitialized) return
        val notification = marqueeNotificationController.pollNext() ?: return
        activeMarqueeNotification = notification
        val marquee = binding.txtMarquee
        marquee.removeCallbacks(marqueeNotificationDismiss)
        marquee.animate().cancel()
        marquee.text = notification.text
        marquee.alpha = 0f
        marquee.translationY = dp(4).toFloat()
        marquee.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MARQUEE_FADE_IN_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (activeMarqueeNotification === notification) {
                    marquee.postDelayed(marqueeNotificationDismiss, MARQUEE_VISIBLE_MS)
                }
            }
            .start()
    }

    private fun dismissMarqueeNotification() {
        val notification = activeMarqueeNotification ?: return
        if (!::binding.isInitialized) return
        val marquee = binding.txtMarquee
        marquee.animate().cancel()
        marquee.animate()
            .alpha(0f)
            .translationY(-dp(4).toFloat())
            .setDuration(MARQUEE_FADE_OUT_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (activeMarqueeNotification !== notification) return@withEndAction
                activeMarqueeNotification = null
                marquee.text = baselineMarqueeText
                marquee.alpha = 1f
                marquee.translationY = 0f
                showNextMarqueeNotification()
            }
            .start()
    }

    override fun onToast(text: String) {
        if (text.isNotBlank()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onHistoryUpdated() {
        if (::kioskController.isInitialized) {
            kioskController.onHistoryUpdated()
        }
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
            audioOverlayBinding?.txtAudioLyricCurrent?.stopAnimation()
            showStandby()
            return
        }

        updatePlayerInfo(snapshot)

        val eng = engine ?: return
        val volume = snapshot.volume
        val muted = snapshot.muted

        // 同一首：只处理播放/暂停 + 音量，不重新装载
        if (playing.queueId == currentQueueId && playbackReplacementJob?.isActive == true) {
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
        audioOverlayBinding?.txtAudioLyricCurrent?.stopAnimation()
        audioOverlayBinding?.txtAudioLyricNext?.text = ""
        showPlayer(audioMode)
        if (audioMode) {
            val audioUi = requireNotNull(audioOverlayBinding)
            val song = playing.song
            audioUi.imgAudioCover.setImageDrawable(null)
            audioUi.txtAudioFallback.text = song?.title.orEmpty().take(2).ifBlank { "KTV" }
            audioUi.txtAudioTitle.text = song?.title.orEmpty()
            audioUi.txtAudioArtist.text = song?.artist.orEmpty()
            audioUi.txtAudioLyricCurrent.setLine(null, 0L)
            audioUi.txtAudioLyricNext.text = song?.title.orEmpty()
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
        playbackAssetsJob?.cancel()
        playbackAssetsJob = null
        accompanimentTrackIndex = file.audioLayout.accompanimentTrackIndex ?: file.vocalTrackIndex
        audioTrackCount = file.audioTracks
        currentAudioLayout = file.audioLayout
        currentFileId = file.id
        val command = playbackLoadProjection.commandForLoadedFile(
            queueId = targetQueueId,
            fileId = file.id,
            streamUrl = streamUrl,
            accompanimentTrackIndex = accompanimentTrackIndex,
            audioTrackCount = audioTrackCount,
            audioLayout = currentAudioLayout,
            recovery = token.request.takeIf {
                LiveTranscodeStreamUrl.isLiveTranscode(streamUrl)
            },
        ) ?: return
        eng.setVocalMode(
            command.vocalMode,
            command.accompanimentTrackIndex,
            command.audioTrackCount,
            command.audioLayout,
        )
        eng.applyVolume(command.volume, command.muted)
        if (!playbackCoordinator.isCurrent(token) || !isCurrentPlaybackLoad(loadTicket)) return
        val hasVideoDeclared = !snapshot.playing?.song?.mediaType.equals("AUDIO", ignoreCase = true)
        eng.play(
            command.fileId,
            command.streamUrl,
            command.queueId,
            command.playWhenReady,
            command.positionMs,
            hasVideoDeclared = hasVideoDeclared,
            format = file.format,
            songDurationMs = snapshot.playing?.song?.durationMs?.toLong() ?: 0L,
        )
        if (!playbackCoordinator.isCurrent(token) || !isCurrentPlaybackLoad(loadTicket)) return
        loadedQueueId = targetQueueId
        playbackSeekGate.markApplied(command.seekSequence)
        playbackAssetsJob = lifecycleScope.launch {
            supervisorScope {
                launch {
                    try {
                        val text = mediaApi.fetchLyric(songId)
                        val lines = withContext(Dispatchers.Default) {
                            text?.let(LrcParser::parse).orEmpty()
                        }
                        if (isCurrentPlaybackAssets(loadTicket, token, file.id)) {
                            lyricLines = lines
                            lastLyricIndex = -1
                            updateProgress(eng.currentPositionMs)
                            if (audioMode && lines.isNotEmpty()) {
                                audioOverlayBinding?.txtAudioLyricNext?.text = lines.first().text
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w("MainActivity", "optional lyric load failed: " + failure.javaClass.simpleName)
                    }
                }
                if (audioMode) {
                    val avatarFallbackUrl = snapshot.playing?.song?.artistAvatarUrl?.trim()?.takeIf { it.isNotEmpty() }
                    launch {
                        try {
                            val coverBytes = mediaApi.fetchCover(songId)
                            var bitmap = if (coverBytes != null) {
                                withContext(Dispatchers.Default) {
                                    ArtworkDecoder.decode(coverBytes, ArtworkProfile.COVER)
                                }
                            } else {
                                null
                            }
                            if (bitmap == null && avatarFallbackUrl != null) {
                                bitmap = sharedAvatarCache[avatarFallbackUrl]
                                    ?: mediaApi.fetchUrl(avatarFallbackUrl)?.let { avatarBytes ->
                                        withContext(Dispatchers.Default) {
                                            ArtworkDecoder.decode(avatarBytes, ArtworkProfile.AVATAR)
                                        }?.also { decoded ->
                                            sharedAvatarCache[avatarFallbackUrl] = decoded
                                        }
                                    }
                            }
                            if (bitmap != null && isCurrentPlaybackAssets(loadTicket, token, file.id)) {
                                ensureAudioOverlayBinding().imgAudioCover.setImageBitmap(bitmap)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Log.w("MainActivity", "optional artwork load failed: " + failure.javaClass.simpleName)
                        }
                    }
                }
            }
        }
    }

    private fun isCurrentPlaybackAssets(
        ticket: PlaybackLoadTicket,
        token: PlaybackReplacementToken,
        fileId: Long,
    ): Boolean = isCurrentPlaybackLoad(ticket) && playbackCoordinator.isCurrent(token) &&
        loadedQueueId == token.request.queueId && currentFileId == fileId

    private fun beginPlaybackLoad(queueId: Long?): PlaybackLoadTicket {
        playbackAssetsJob?.cancel()
        playbackAssetsJob = null
        playbackReplacementJob?.cancel()
        playbackReplacementJob = null
        return playbackLoadGate.begin(queueId)
    }

    private fun isCurrentPlaybackLoad(ticket: PlaybackLoadTicket): Boolean =
        playbackLoadGate.isCurrent(ticket)

    private fun invalidatePlaybackLoad() {
        playbackAssetsJob?.cancel()
        playbackAssetsJob = null
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

    // ---- 二维码（P1.30） ----

    /** 拉取服务端按当前请求地址生成的 H5 二维码，并同步到 PiP 和音频遮罩。 */
    private fun loadQr() {
        if (qrLoadJob?.isActive == true) return
        qrLoadJob = lifecycleScope.launch {
            try {
                val bytes = mediaApi.fetchQr(QR_SIZE_PX)
                val bmp = bytes?.let {
                    withContext(Dispatchers.Default) {
                        ArtworkDecoder.decode(it, ArtworkProfile.QR)
                    }
                }
                if (bmp != null) {
                    cachedKioskQrBitmap = bmp
                    bottomDockController.setQrBitmap(bmp)
                    binding.imgMiniQr.setImageBitmap(bmp)
                    audioOverlayBinding?.imgAudioMiniQr?.setImageBitmap(bmp)
                    topBarQrDialog?.updateQrBitmap(bmp)
                    if (::kioskController.isInitialized) {
                        kioskController.setCachedQrBitmap(bmp)
                    }
                } else {
                    topBarQrDialog?.showLoadError("二维码暂不可用，请检查 Home KTV 服务连接后重试。", retryable = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("MainActivity", "QR loading failed", e)
                topBarQrDialog?.showLoadError("二维码加载失败，请检查 Home KTV 服务连接后重试。", retryable = true)
            } finally {
                qrLoadJob = null
            }
        }
    }

    private fun loadDisplayContent() {
        lifecycleScope.launch {
            mediaApi.fetchStandbyContent()?.let(::applyDisplayContent)
        }
    }

    private fun applyDisplayContent(content: StandbyContent) {
        applyVideoScaleMode(content.videoScaleMode)
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
        audioMiniQrVisibility = miniQrVisibility
        audioMiniQrLabelVisibility = miniQrLabelVisibility
        audioOverlayBinding?.imgAudioMiniQr?.visibility = miniQrVisibility
        audioOverlayBinding?.txtAudioMiniQrHint?.visibility = miniQrLabelVisibility
    }

    private fun restoreMicrophoneMonitorIfPermitted() {
        val recordAudioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (MicrophoneMonitorStartupPolicy.shouldRestoreMonitoring(config.microphoneMonitorEnabled, recordAudioGranted)) {
            ensureMicrophonePermissionsAndStart()
        } else if (config.microphoneMonitorEnabled) {
            config.microphoneMonitorEnabled = false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showPlayer(audioMode: Boolean = false) {
        if (audioMode) {
            if (bottomDockController.isFullscreen) bottomDockController.exitFullscreen()
            bottomDockController.setVideoVisible(false)
        } else {
            bottomDockController.setVideoVisible(hasCurrentSong && !externalDisplayActive)
        }
        binding.ktvOverlay.visibility = if (bottomDockController.isFullscreen && !audioMode) View.VISIBLE else View.GONE
        if (audioMode) {
            ensureAudioOverlayBinding().root.visibility = View.VISIBLE
        } else {
            audioOverlayBinding?.root?.visibility = View.GONE
        }
        if (bottomDockController.isFullscreen && !audioMode) renderFullscreenPlaybackPanel()
    }

    private fun showStandby() {
        if (bottomDockController.isFullscreen) bottomDockController.exitFullscreen()
        bottomDockController.setVideoVisible(false)
        binding.ktvOverlay.visibility = View.GONE
        audioOverlayBinding?.root?.visibility = View.GONE
        hidePlaybackProgress()
        binding.pageContainer.visibility = View.VISIBLE
        ensureKioskController().toggleKiosk(true)
    }

    private fun scheduleDisplaySettingsTicker() {
        binding.root.removeCallbacks(displaySettingsTicker)
        binding.root.postDelayed(displaySettingsTicker, 60_000L)
    }

    private fun removeDisplaySettingsTicker() {
        if (!::binding.isInitialized) return
        binding.root.removeCallbacks(displaySettingsTicker)
    }

    private fun showPlaybackProgress() {
        if (!::binding.isInitialized || !bottomDockController.isFullscreen) return
        binding.playerInfoPanel.visibility = View.VISIBLE
        binding.playProgress.visibility = View.VISIBLE
        audioOverlayBinding?.audioProgress?.visibility = View.VISIBLE
        binding.txtElapsed.visibility = View.VISIBLE
        binding.txtVocalMode.visibility = View.VISIBLE
        binding.txtDuration.visibility = View.VISIBLE
        val showLyrics = lyricLines.isNotEmpty()
        binding.txtLyricPrevious.visibility = if (showLyrics) View.VISIBLE else View.GONE
        binding.txtLyricCurrent.visibility = if (showLyrics) View.VISIBLE else View.GONE
        clock.removeCallbacks(progressHide)
        clock.postDelayed(progressHide, PROGRESS_HIDE_DELAY_MS)
    }

    private fun hidePlaybackProgress() {
        if (!::binding.isInitialized) return
        binding.playerInfoPanel.visibility = View.GONE
        binding.playProgress.visibility = View.GONE
        val audioUi = audioOverlayBinding
        val audioMode = audioUi?.root?.visibility == View.VISIBLE
        audioUi?.audioProgress?.visibility = if (audioMode) View.VISIBLE else View.GONE
        audioUi?.txtAudioElapsed?.visibility = if (audioMode) View.VISIBLE else View.GONE
        audioUi?.txtAudioDuration?.visibility = if (audioMode) View.VISIBLE else View.GONE
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
        val audioUi = if (song.mediaType.equals("AUDIO", ignoreCase = true)) {
            ensureAudioOverlayBinding()
        } else {
            audioOverlayBinding
        }
        binding.txtMediaBadge.text = if (song.mediaType == "KTV_VIDEO") "KTV版" else "MV"
        binding.txtPlayerTitle.text = song.title
        binding.txtPlayerArtist.text = song.artist
        updateArtistAvatar(song.artistAvatarUrl)
        binding.txtOrderedBy.text = current.orderedByNick?.let { "$it 点" } ?: ""
        val next = snapshot.list.firstOrNull { it.status == "waiting" }
        binding.nextPanel.visibility = if (next?.song != null) View.VISIBLE else View.GONE
        binding.txtNextSong.text = next?.song?.let { "${it.title} · ${next.orderedByNick ?: ""}" } ?: ""
        audioUi?.txtAudioNext?.text = next?.song?.let { "接下来  ${it.title} · ${it.artist}" } ?: ""
        // Lyric text is supplied independently; do not synthesize it from the song title.
        if (lyricLines.isEmpty()) {
            // KTV video subtitles are commonly burned into the MV. Duplicating the song title
            // in the lyric band obscures those subtitles when no external lyric file exists.
            binding.txtLyricCurrent.text = ""
            binding.txtLyricPrevious.setLine(null, 0L)
            binding.txtLyricPrevious.visibility = View.GONE
            binding.txtLyricCurrent.visibility = View.GONE
        }
        binding.txtVocalMode.text = if (supportsVocalSwitch(snapshot.audioLayout, audioTrackCount)) {
            if (snapshot.vocalMode == "original") "原唱中" else "伴唱中"
        } else ""
        binding.txtDuration.text = formatMs(song.durationMs.toLong())
        renderFullscreenPlaybackPanel(snapshot.positionMs)
    }

    private fun resolveAvatarCacheBudgetBytes(): Long {
        val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val isLowRam = am?.isLowRamDevice == true
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        return if (isLowRam || (totalMb in 1L..2300L)) 8L * 1024L * 1024L else 16L * 1024L * 1024L
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                sharedAvatarCache.clear()
                kioskViewModel.clearSearchCache()
                cachedKioskQrBitmap = null
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                sharedAvatarCache.trimToBytes(avatarCacheMaxBytes / 2L)
            }
        }
    }

    /** Loads the cached server avatar without blocking playback or allowing stale songs to win. */
    private fun updateArtistAvatar(rawUrl: String?) {
        if (!::binding.isInitialized) return
        val url = rawUrl?.trim()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val cached = url?.let { sharedAvatarCache[it] }
        if (url == artistAvatarUrl && (cached != null || now - artistAvatarRequestAt < 30_000L)) return

        artistAvatarUrl = url
        artistAvatarRequestAt = now
        val requestId = ++artistAvatarRequestId
        artistAvatarJob?.cancel()
        artistAvatarJob = null
        binding.imgPlayerArtistAvatar.setImageDrawable(null)
        binding.imgPlayerArtistAvatar.visibility = View.GONE
        audioOverlayBinding?.imgAudioArtistAvatar?.setImageDrawable(null)
        audioOverlayBinding?.imgAudioArtistAvatar?.visibility = View.GONE
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
                sharedAvatarCache[url] = bitmap
            }
            if (requestId != artistAvatarRequestId || url != artistAvatarUrl) return@launch
            if (bitmap != null) showArtistAvatar(bitmap)
        }
    }

    private fun showArtistAvatar(bitmap: android.graphics.Bitmap) {
        binding.imgPlayerArtistAvatar.setImageBitmap(bitmap)
        binding.imgPlayerArtistAvatar.visibility = View.VISIBLE
        audioOverlayBinding?.imgAudioArtistAvatar?.setImageBitmap(bitmap)
        audioOverlayBinding?.imgAudioArtistAvatar?.visibility = View.VISIBLE
    }

    private fun updateProgress(positionMs: Long) {
        val ktvOverlayVisible = ::binding.isInitialized && binding.ktvOverlay.visibility == View.VISIBLE
        val audioUi = audioOverlayBinding
        val audioOverlayVisible = audioUi?.root?.visibility == View.VISIBLE
        if (!ktvOverlayVisible && !audioOverlayVisible) {
            return
        }
        val duration = engine?.durationMs ?: 0L
        val progressVal = if (duration > 0) ((positionMs * 1000) / duration).toInt().coerceIn(0, 1000) else 0
        val elapsedSec = TimeUnit.MILLISECONDS.toSeconds(positionMs.coerceAtLeast(0))
        val elapsedChanged = elapsedSec != lastElapsedSecond
        val formattedElapsed = if (elapsedChanged) formatMs(positionMs).also { lastElapsedSecond = elapsedSec } else null

        if (ktvOverlayVisible) {
            binding.playProgress.progress = progressVal
            if (formattedElapsed != null) {
                binding.txtElapsed.text = formattedElapsed
            }
            renderFullscreenPlaybackPanel(positionMs)
        }
        if (audioUi != null && audioOverlayVisible) {
            audioUi.audioProgress.progress = progressVal
            if (formattedElapsed != null) {
                audioUi.txtAudioElapsed.text = formattedElapsed
            }
            val durationSec = TimeUnit.MILLISECONDS.toSeconds(duration.coerceAtLeast(0))
            if (durationSec != lastAudioDurationSecond) {
                lastAudioDurationSecond = durationSec
                audioUi.txtAudioDuration.text = formatMs(duration)
            }
        }
        if (lyricLines.isNotEmpty()) {
            if (ktvOverlayVisible) {
                binding.txtLyricPrevious.visibility = View.VISIBLE
                binding.txtLyricCurrent.visibility = View.VISIBLE
            }
            val index = findLyricIndex(lyricLines, positionMs)
            val indexChanged = index != lastLyricIndex
            if (indexChanged) {
                lastLyricIndex = index
                val nextLineText = lyricLines.getOrNull(index + 1)?.text.orEmpty()
                if (ktvOverlayVisible) {
                    binding.txtLyricPrevious.animate().cancel()
                    binding.txtLyricCurrent.animate().cancel()
                    binding.txtLyricPrevious.alpha = 0.55f
                    binding.txtLyricCurrent.alpha = 0.35f
                    binding.txtLyricPrevious.animate().alpha(1f).setDuration(180L).start()
                    binding.txtLyricCurrent.animate().alpha(1f).setDuration(240L).start()
                    binding.txtLyricCurrent.text = nextLineText
                }
                if (audioUi != null && audioOverlayVisible) {
                    audioUi.txtAudioLyricNext.text = nextLineText
                }
            }
            // 常规点歌机布局：当前演唱行在上，下一行预告在下。
            val lineEndMs = lyricLines.getOrNull(index + 1)?.startMs
                ?: duration.takeIf { it > lyricLines[index].startMs }
                ?: lyricLines[index].startMs + 5_000L
            val isPlayingNow = currentPlaybackState == "playing"
            if (ktvOverlayVisible) {
                binding.txtLyricPrevious.setLine(lyricLines[index], lineEndMs)
                binding.txtLyricPrevious.updatePlayback(positionMs, isPlayingNow)
            }
            if (audioUi != null && audioOverlayVisible) {
                audioUi.txtAudioLyricCurrent.setLine(lyricLines[index], lineEndMs)
                audioUi.txtAudioLyricCurrent.updatePlayback(positionMs, isPlayingNow)
            }
        }
    }

    private fun findLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun renderFullscreenPlaybackPanel(fallbackPositionMs: Long? = null) {
        if (!::fullscreenPlaybackPanel.isInitialized) return
        if (!bottomDockController.isFullscreen && binding.ktvOverlay.visibility != View.VISIBLE) return
        val snapshot = cachedQueueSnapshot
        val song = snapshot?.playing?.song
        val loadedCurrent = snapshot?.playing?.queueId == loadedQueueId
        val position = if (loadedCurrent) engine?.currentPositionMs ?: fallbackPositionMs ?: snapshot?.positionMs ?: 0L
            else fallbackPositionMs ?: snapshot?.positionMs ?: 0L
        val duration = (engine?.durationMs?.takeIf { loadedCurrent && it > 0L })
            ?: song?.durationMs?.toLong()?.takeIf { it > 0L }
            ?: 0L
        val nextSong = snapshot?.list?.firstOrNull()?.song
        fullscreenPlaybackPanel.updatePlayback(
            title = song?.title,
            position = position,
            duration = duration,
            isPlaying = currentPlaybackState == "playing",
            currentVocalMode = currentVocalMode,
            isMuted = currentMuted,
            canSeek = loadedCurrent && currentFileId != null,
            nextTitle = nextSong?.title,
            nextArtist = nextSong?.artist,
            autoPlay = snapshot?.playing?.autoPlay == true,
        )
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val sb = StringBuilder(5)
        if (minutes < 10) sb.append('0')
        sb.append(minutes).append(':')
        if (seconds < 10) sb.append('0')
        sb.append(seconds)
        return sb.toString()
    }

    /** 播放失败：通知服务端推进队列，不将一次客户端播放失败视为源文件失效。 */
    private fun onPlayError(context: PlaybackErrorContext, bypassReplacementGuard: Boolean = false) {
        if (!bypassReplacementGuard && playbackReplacementJob?.isActive == true) return
        Toast.makeText(this, R.string.play_error, Toast.LENGTH_SHORT).show()
        socket?.sendPlayError("media playback failed", context.fileId, context.queueId)
    }

    companion object {
        @Volatile
        private var startupScanAttemptedInProcess = false
        /** 二维码请求边长（服务端在 [120,1080] 内钳制）；待机占位框 220dp，取 540 兼顾高密度电视清晰度。 */
        private const val QR_SIZE_PX = 540
        private const val MARQUEE_VISIBLE_MS = 5_000L
        private const val MARQUEE_FADE_IN_MS = 240L
        private const val MARQUEE_FADE_OUT_MS = 260L
        private const val PROGRESS_HIDE_DELAY_MS = 5_000L
        private const val VOCAL_CHANGED_EVENT = "vocal_changed"
    }
}

