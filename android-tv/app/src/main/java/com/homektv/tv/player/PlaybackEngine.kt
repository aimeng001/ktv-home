package com.homektv.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.PlaybackErrorContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ExoPlayer 流式播放引擎（P1.28，详设§6/§9.2）。
 *
 * - 通过 OkHttpDataSource 拉 GET /api/stream/{file_id}（服务端 P1.17 已支持 HTTP Range，
 *   MKV/MP4/MP3/FLAC 秒开、可 seek）。
 * - 播放中每 100ms 回调 [onProgress]（TV 歌词平滑刷新；调用方将上行进度限流）。
 * - 自然播完回调 [onFinished]（供 P1.33 自动连播）。
 * - 播放错误回调 [onError]（供 P1.34 上报 play_error）。
 *
 * 所有回调在主线程分发。
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackEngine(
    context: Context,
    private val onProgress: (positionMs: Long, queueId: Long?) -> Unit,
    private val onFinished: (queueId: Long?) -> Unit,
    private val onError: (message: String, context: PlaybackErrorContext) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    /** 系统音量被外部（遥控音量键→系统媒体会话路径）改变时回调，参数为 0-100。 */
    var onExternalVolumeChange: ((volumePercent: Int) -> Unit)? = null

    /** 防止 applyVolume 自己写系统音量时被观察者误判为外部变更。 */
    @Volatile private var applyingSystemVolume = false
    private var lastAppliedStreamIdx = -1

    // 腾讯极光等 ROM 在系统层拦截音量键直送媒体会话，App 的 dispatchKeyEvent 根本收不到；
    // 这里反过来监听系统媒体音量变化，上行同步到服务端，保证 App/H5/系统三方一致。
    private val systemVolumeObserver = object : android.database.ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (applyingSystemVolume) return
            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val idx = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            if (idx == lastAppliedStreamIdx) return
            lastAppliedStreamIdx = idx
            val percent = (idx * 100 + max / 2) / max
            Log.d(TAG, "system volume changed externally: idx=$idx/$max -> $percent%")
            onExternalVolumeChange?.invoke(percent)
        }
    }

    init {
        appContext.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, systemVolumeObserver)
    }

    /** 当前正在播放的 fileId，用于避免同一首重复 setMediaItem 打断播放。 */
    private var currentFileId: Long? = null
    /** 与当前媒体一起捕获的 queueId，避免迟到错误使用后来切歌后的队列状态。 */
    private var currentQueueId: Long? = null
    /** 只有同一队列中的同一文件才允许复用已加载的媒体。 */
    private var currentRequest: PlaybackRequestIdentity? = null
    /** Callback identity is committed only after the requested media reaches READY. */
    private val identityGate = PlaybackIdentityGate()
    private var requestedVocalMode: String = "original"
    private var requestedAccompanimentIndex: Int? = null
    private var requestedAudioTrackCount: Int = 1
    private var requestedAudioLayout: AudioLayout = AudioLayout.normalStereo()
    private var vocalRequestAt: Long = 0L
    private var playRequestAt: Long = 0L
    private var awaitingTracks = false
    /** 最后一帧视频上屏时间（elapsedRealtime）；0 表示尚无帧。 */
    private var lastVideoFrameAt: Long = 0L
    private var videoStallRecoveries = 0
    private var appliedSelectionFileId: Long? = null
    private var appliedSelectionMode: String? = null
    private var appliedSelectionGroup: androidx.media3.common.TrackGroup? = null
    private var appliedSelectionTrack: Int? = null
    private val channelAudioProcessor = KtvChannelAudioProcessor()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 读超时收紧到 8s：链路（盒子 WiFi / Mac 休眠节流）会偶发整段停供数据，
        // 实测重试新连接可立即恢复。8s 超时 + 25s 缓冲可把这类停顿隐藏在缓冲内。
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val player: ExoPlayer by lazy { buildPlayer() }

    private fun buildPlayer(): ExoPlayer {
        val dataSourceFactory: DataSource.Factory =
            OkHttpDataSource.Factory(httpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        // 缓冲上限控制在 ~25s：真机（腾讯极光 6SE）实测，HTTP 拉流连接空闲 ~60s
        // 后会被链路中间设备掐断（unexpected end of stream），且长时间无流量会
        // 触发盒子 WiFi 省电导致 30s 级读超时。保持小缓冲让连接持续有数据流动，
        // 避免这两种停顿；25s 缓冲对局域网播放已足够。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                25_000,
                2_500,
                5_000,
            )
            .build()
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            @Suppress("DEPRECATION")
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder()
                // Media3 1.4.1 only honors custom capabilities when the builder has no Context.
                // Restricting this sink to PCM prevents encoded passthrough from bypassing the
                // channel processor. DUAL_TRACK still uses the same decoded audio TrackSelection.
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(channelAudioProcessor))
                .build()
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(playerListener)
                it.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
                // 视频帧看门狗（P1.35 腾讯极光等盒子实测）：部分机顶盒的 MPEG-2 硬解
                // 在暂停/恢复后会停止出帧，音频照常走、画面定格。记录每帧上屏时间，
                // 由 progressTicker 检测并就地 seek 强制 flush 解码器恢复。
                it.setVideoFrameMetadataListener { _, _, _, _ ->
                    lastVideoFrameAt = SystemClock.elapsedRealtime()
                    videoStallRecoveries = 0
                }
            }
    }

    /** 绑定/解绑显示层（切到播放页时绑，回待机时解绑）。 */
    fun attach(view: PlayerView) {
        view.player = player
    }

    fun detach(view: PlayerView) {
        if (view.player === player) view.player = null
    }

    /**
     * 播放指定文件源。若已在同一队列播放同一文件则不打断（只确保处于播放态）。
     * @param fileId song_files.id
     * @param streamUrl 完整拉流地址
     */
    fun play(fileId: Long, streamUrl: String, queueId: Long? = null) {
        val requested = PlaybackRequestIdentity(queueId = queueId, fileId = fileId)
        if (shouldReusePlaybackRequest(currentRequest, requested, player.playbackState)) {
            player.playWhenReady = true
            return
        }
        Log.d(TAG, "play fileId=$fileId url=$streamUrl")
        transientRetryCount = 0
        videoStallRecoveries = 0
        lastVideoFrameAt = 0L
        playRequestAt = SystemClock.elapsedRealtime()
        currentRequest = requested
        currentQueueId = queueId
        currentFileId = fileId
        identityGate.begin(
            PlaybackIdentity(
                queueId = queueId,
                fileId = fileId,
                mediaId = fileId.toString(),
            ),
        )
        awaitingTracks = true
        appliedSelectionFileId = null
        appliedSelectionMode = null
        appliedSelectionGroup = null
        appliedSelectionTrack = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        stopProgressTicker()
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(fileId.toString())
                .setUri(streamUrl)
                .build(),
        )
        player.prepare()
        player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun resume() {
        player.playWhenReady = true
    }

    /** 回到片头（对应 restart 控制）。 */
    fun restart() {
        player.seekTo(0)
        player.playWhenReady = true
    }

    /** Applies a server-requested seek without reloading the current media item. */
    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * 停止并清空（队列空回待机时调用）。清 currentFileId，
     * 使下次同 fileId 也会重新装载。
     */
    fun stop() {
        currentRequest = null
        currentQueueId = null
        currentFileId = null
        identityGate.invalidate()
        playRequestAt = 0L
        awaitingTracks = false
        appliedSelectionFileId = null
        appliedSelectionMode = null
        appliedSelectionGroup = null
        appliedSelectionTrack = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        player.stop()
        player.clearMediaItems()
        stopProgressTicker()
    }

    /** 音量 0-100 映射到系统媒体音量（静音单独用 player.volume=0）。
     *  真机实测（腾讯极光 6SE）：只调 ExoPlayer 音量会让系统音量被独立调低后
     *  无法在 App 内恢复（音量键被 App 接管），因此直接联动系统音量。 */
    fun applyVolume(volume: Int, muted: Boolean) {
        val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val target = volume.coerceIn(0, 100) * max / 100
        if (!muted) {
            if (audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) != target) {
                applyingSystemVolume = true
                try {
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                } finally {
                    applyingSystemVolume = false
                }
            }
            lastAppliedStreamIdx = target
        }
        player.volume = if (muted) 0f else 1f
    }

    /** 在当前音频轨组内切换原唱/伴唱，避免重新加载媒体。 */
    fun setVocalMode(
        mode: String,
        accompanimentIndex: Int?,
        audioTrackCount: Int,
        audioLayout: AudioLayout = AudioLayout.normalStereo(),
    ) {
        requestedVocalMode = mode
        requestedAccompanimentIndex = accompanimentIndex
        requestedAudioTrackCount = audioTrackCount
        requestedAudioLayout = audioLayout
        vocalRequestAt = SystemClock.elapsedRealtime()
        applyVocalSelection()
    }

    private fun applyVocalSelection() {
        when (AudioPlaybackRoute.forLayout(requestedAudioLayout.layout)) {
            AudioPlaybackRoute.PASSTHROUGH -> {
                channelAudioProcessor.setMode(PcmChannelMode.STEREO)
                return
            }
            AudioPlaybackRoute.PCM_CHANNEL_MAPPING -> {
                // This changes only the next PCM buffer's channel mapping. It never touches the
                // MediaItem, prepare state, or currentPosition.
                channelAudioProcessor.setMode(
                    PcmChannelMapper.modeFor(
                        requestedVocalMode,
                        requestedAudioLayout.originalChannel,
                        requestedAudioLayout.accompanimentChannel,
                    ),
                )
                return
            }
            AudioPlaybackRoute.TRACK_SELECTION -> {
                channelAudioProcessor.setMode(PcmChannelMode.STEREO)
                if (awaitingTracks) return
            }
        }
        val index = resolveAudioTrackIndex(
            audioLayout = requestedAudioLayout,
            vocalMode = requestedVocalMode,
            audioTrackCount = requestedAudioTrackCount,
            legacyAccompanimentIndex = requestedAccompanimentIndex,
        )
        if (index == null || index < 0) return
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) return
        var remaining = index
        var group: androidx.media3.common.TrackGroup? = null
        var localIndex = 0
        for (candidate in audioGroups) {
            if (remaining < candidate.length) {
                group = candidate.mediaTrackGroup
                localIndex = remaining
                break
            }
            remaining -= candidate.length
        }
        if (group == null) {
            Log.w(TAG, "audio track index=$index unavailable groups=${audioGroups.map { it.length }}")
            return
        }
        if (appliedSelectionFileId == currentFileId &&
            appliedSelectionMode == requestedVocalMode &&
            appliedSelectionGroup == group &&
            appliedSelectionTrack == localIndex
        ) {
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, listOf(localIndex)))
            .build()
        appliedSelectionFileId = currentFileId
        appliedSelectionMode = requestedVocalMode
        appliedSelectionGroup = group
        appliedSelectionTrack = localIndex
        val elapsed = SystemClock.elapsedRealtime() - vocalRequestAt
        Log.d(TAG, "vocal mode=$requestedVocalMode track=$index groupTrack=$localIndex groups=${audioGroups.map { it.length }} applied in ${elapsed}ms")
    }

    /** 当前媒体时长，供 TV-02 进度条展示。 */
    val durationMs: Long get() = player.duration.takeIf { it > 0 } ?: 0L
    val currentPositionMs: Long get() = player.currentPosition.coerceAtLeast(0L)

    fun release() {
        stopProgressTicker()
        identityGate.invalidate()
        appContext.contentResolver.unregisterContentObserver(systemVolumeObserver)
        player.release()
    }

    // ---- 高频本地进度采样 ----

    private val progressTicker = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                identityGate.callbackIdentity()?.let { active ->
                    onProgress(player.currentPosition, active.queueId)
                }
                checkVideoStall()
            }
            main.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    /** 播放中超过 [VIDEO_STALL_THRESHOLD_MS] 没有视频帧上屏 → 就地 seek 强制 flush 解码器。 */
    private fun checkVideoStall() {
        if (player.videoFormat == null) return // 纯音频源无视频轨
        if (lastVideoFrameAt == 0L) return   // 起播阶段由 onIsPlayingChanged 路径负责
        val stalledMs = SystemClock.elapsedRealtime() - lastVideoFrameAt
        if (stalledMs < VIDEO_STALL_THRESHOLD_MS) return
        if (videoStallRecoveries >= MAX_VIDEO_STALL_RECOVERIES) {
            Log.w(TAG, "video stall persists after $videoStallRecoveries recoveries, give up")
            lastVideoFrameAt = SystemClock.elapsedRealtime() // 避免每秒刷日志
            return
        }
        videoStallRecoveries++
        val position = player.currentPosition
        Log.w(TAG, "video stall ${stalledMs}ms at position=$position, recovery #$videoStallRecoveries (in-place seek)")
        player.seekTo(position)
        lastVideoFrameAt = SystemClock.elapsedRealtime()
    }

    private fun startProgressTicker() {
        main.removeCallbacks(progressTicker)
        main.postDelayed(progressTicker, PROGRESS_INTERVAL_MS)
    }

    private fun stopProgressTicker() {
        main.removeCallbacks(progressTicker)
    }

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (tracks.groups.none { it.type == C.TRACK_TYPE_AUDIO }) return
            awaitingTracks = false
            applyVocalSelection()
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_ENDED -> {
                    Log.d(TAG, "playback ended fileId=$currentFileId")
                    stopProgressTicker()
                    identityGate.consumeFinishedIdentity()?.let { finished ->
                        onFinished(finished.queueId)
                    }
                }
                Player.STATE_READY -> {
                    val mediaId = player.currentMediaItem?.mediaId
                    if (mediaId != null) {
                        identityGate.markReady(mediaId)
                    }
                    if (identityGate.activeIdentity() != null) {
                        startProgressTicker()
                    }
                }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying || playRequestAt == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - playRequestAt
            Log.i(TAG, "playback started fileId=$currentFileId in ${elapsed}ms")
            playRequestAt = 0L
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "player error: ${error.errorCodeName} ${error.message}")
            if (isTransient(error) && transientRetryCount < MAX_TRANSIENT_RETRIES && currentFileId != null) {
                transientRetryCount++
                val position = player.currentPosition
                Log.w(TAG, "retry media fileId=$currentFileId attempt=$transientRetryCount position=$position")
                main.postDelayed({
                    if (currentFileId != null) {
                        player.prepare()
                        player.seekTo(position)
                        player.playWhenReady = true
                        startProgressTicker()
                    }
                }, RETRY_DELAY_MS)
                return
            }
            transientRetryCount = 0
            stopProgressTicker()
            identityGate.invalidate()
            onError(error.errorCodeName, PlaybackErrorContext.forPlayback(currentQueueId, currentFileId))
        }

        private fun isTransient(error: PlaybackException): Boolean = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "PlaybackEngine"
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val MAX_TRANSIENT_RETRIES = 2
        private const val RETRY_DELAY_MS = 750L
        private const val VIDEO_STALL_THRESHOLD_MS = 3_000L
        private const val MAX_VIDEO_STALL_RECOVERIES = 3
    }

    private var transientRetryCount = 0

}
