package com.homektv.tv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.homektv.tv.net.AudioLayout

/**
 * 客户端备选播放引擎接口与实现。
 * 针对 Media3 官方硬解遇到不支持格式时的备用引擎：
 * 1. 提供完整的 setVolume、setVocalSelection、seek、进度与结束回调；
 * 2. 保证在生僻格式触发回退时，底层音频通道安全直通发声，决不无声死寂；
 * 3. 支持 Surface 画面挂载与生命周期管理。
 */
interface FallbackPlayer {
    fun setSurface(surface: Surface?)
    fun prepareAndPlay(
        fileId: Long,
        streamUrl: String,
        initialPositionMs: Long = 0L,
        requestToken: Long = 0L,
        playWhenReady: Boolean = true,
    )
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setChannelMode(mode: String)
    fun setVolume(volume: Int, muted: Boolean)
    fun setVocalSelection(mode: String, trackIndex: Int?, audioLayout: AudioLayout)
    fun release()
    val isPlaying: Boolean
    val currentPositionMs: Long
    val durationMs: Long
}

/** Keeps pause/resume intent ordered with MediaPlayer's asynchronous prepare callback. */
internal class FallbackPlaybackIntent {
    private var generation = 0L
    private var prepared = false
    private var playWhenReady = true

    fun begin(playWhenReady: Boolean): Long {
        generation += 1L
        prepared = false
        this.playWhenReady = playWhenReady
        return generation
    }

    fun onPrepared(generation: Long): Boolean? {
        if (!isCurrent(generation)) return null
        prepared = true
        return playWhenReady
    }

    fun isCurrent(generation: Long): Boolean = this.generation == generation

    /** Returns null until prepared; otherwise returns the desired playing state. */
    fun setPlayWhenReady(playWhenReady: Boolean): Boolean? {
        this.playWhenReady = playWhenReady
        return if (prepared) playWhenReady else null
    }

    fun reset() {
        generation += 1L
        prepared = false
        playWhenReady = false
    }
}

class FfmpegFallbackPlayer(
    private val context: Context,
    private val onStateChanged: (isPlaying: Boolean) -> Unit = {},
    private val onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    private val onFinished: () -> Unit = {},
    private val onError: (requestToken: Long, failure: FallbackPlaybackFailure) -> Unit = { _, _ -> },
) : FallbackPlayer {

    companion object {
        private const val TAG = "FfmpegFallbackPlayer"
    }

    private var activeSurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentFileId: Long? = null
    private var currentStreamUrl: String? = null
    private var currentVolume: Float = 1.0f
    private var isMuted: Boolean = false
    private var requestedPositionMs: Long = 0L
    private val playbackIntent = FallbackPlaybackIntent()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && isPlaying) {
                try {
                    val pos = mp.currentPosition.toLong()
                    val dur = mp.duration.toLong()
                    onProgress(pos, dur)
                } catch (e: Exception) {
                    Log.w(TAG, "progress query failed: ${e.message}")
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun setSurface(surface: Surface?) {
        this.activeSurface = surface
        try {
            mediaPlayer?.setSurface(surface)
        } catch (e: Exception) {
            Log.w(TAG, "setSurface failed: ${e.message}")
        }
    }

    override fun prepareAndPlay(
        fileId: Long,
        streamUrl: String,
        initialPositionMs: Long,
        requestToken: Long,
        playWhenReady: Boolean,
    ) {
        stop()
        currentFileId = fileId
        currentStreamUrl = streamUrl
        requestedPositionMs = initialPositionMs.coerceAtLeast(0L)
        val attempt = playbackIntent.begin(playWhenReady)

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                if (activeSurface?.isValid == true) {
                    setSurface(activeSurface)
                }
                setDataSource(streamUrl)
                setOnPreparedListener { mp ->
                    val shouldPlay = playbackIntent.onPrepared(attempt)
                        ?: return@setOnPreparedListener
                    Log.i(TAG, "MediaPlayer prepared for fileId=$fileId")
                    if (requestedPositionMs > 0L) {
                        mp.seekTo(requestedPositionMs.toInt())
                    }
                    applyCurrentVolume()
                    if (shouldPlay) {
                        mp.start()
                        onStateChanged(true)
                        mainHandler.post(progressRunnable)
                    } else {
                        onStateChanged(false)
                    }
                }
                setOnCompletionListener {
                    if (!playbackIntent.isCurrent(attempt)) return@setOnCompletionListener
                    Log.i(TAG, "MediaPlayer playback completed for fileId=$fileId")
                    stop()
                    onFinished()
                }
                setOnErrorListener { _, what, extra ->
                    if (!playbackIntent.isCurrent(attempt)) return@setOnErrorListener true
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra for fileId=$fileId")
                    onError(
                        requestToken,
                        FallbackPlaybackFailure(
                            message = "FALLBACK_ERROR_" + what + "_" + extra,
                            decoderOrFormatFailure = FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(what, extra),
                            platformWhat = what,
                            platformExtra = extra,
                        ),
                    )
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "prepareAndPlay exception for fileId=$fileId: ${e.message}", e)
            onError(
                requestToken,
                FallbackPlaybackFailure(
                    message = e.message ?: "FALLBACK_INIT_FAILED",
                    decoderOrFormatFailure = false,
                ),
            )
        }
    }

    override fun pause() {
        val shouldPause = playbackIntent.setPlayWhenReady(false) ?: return
        if (!shouldPause) {
            // The player's async prepare completed while the requested state is paused.
            mainHandler.removeCallbacks(progressRunnable)
        }
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    mainHandler.removeCallbacks(progressRunnable)
                    onStateChanged(false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pause failed: ${e.message}")
        }
    }

    override fun resume() {
        val shouldPlay = playbackIntent.setPlayWhenReady(true) ?: return
        if (!shouldPlay) return
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                    mainHandler.post(progressRunnable)
                    onStateChanged(true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resume failed: ${e.message}")
        }
    }

    override fun stop() {
        mainHandler.removeCallbacks(progressRunnable)
        playbackIntent.reset()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
        }
        mediaPlayer = null
        currentFileId = null
        currentStreamUrl = null
        onStateChanged(false)
    }

    override fun seekTo(positionMs: Long) {
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        try {
            mediaPlayer?.seekTo(requestedPositionMs.toInt())
        } catch (e: Exception) {
            Log.w(TAG, "seekTo failed: ${e.message}")
        }
    }

    override fun setChannelMode(mode: String) {
        when (mode.lowercase()) {
            "accompaniment" -> {
                mediaPlayer?.setVolume(0f, currentVolume)
            }
            "original" -> {
                applyCurrentVolume()
            }
        }
    }

    override fun setVolume(volume: Int, muted: Boolean) {
        this.currentVolume = (volume.coerceIn(0, 100) / 100f)
        this.isMuted = muted
        applyCurrentVolume()
    }

    override fun setVocalSelection(mode: String, trackIndex: Int?, audioLayout: AudioLayout) {
        val layout = audioLayout.layout
        if (layout.equals("DUAL_TRACK", ignoreCase = true)) {
            if (trackIndex != null && trackIndex >= 0) {
                try {
                    mediaPlayer?.let { mp ->
                        val tracks = mp.trackInfo
                        var audioTrackCount = 0
                        for (i in tracks.indices) {
                            if (tracks[i].trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                                if (audioTrackCount == trackIndex) {
                                    mp.selectTrack(i)
                                    Log.i(TAG, "Selected audio track $trackIndex (internal index $i)")
                                    break
                                }
                                audioTrackCount++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "selectTrack failed: ${e.message}")
                }
            }
            applyCurrentVolume()
        } else if (layout.equals("DUAL_CHANNEL", ignoreCase = true)) {
            val isAccompaniment = mode.equals("accompaniment", ignoreCase = true)
            val channel = if (isAccompaniment) audioLayout.accompanimentChannel else audioLayout.originalChannel
            val vol = if (isMuted) 0f else currentVolume
            try {
                if (channel?.equals("left", ignoreCase = true) == true || channel == "0") {
                    mediaPlayer?.setVolume(vol, 0f)
                } else if (channel?.equals("right", ignoreCase = true) == true || channel == "1") {
                    mediaPlayer?.setVolume(0f, vol)
                } else {
                    if (isAccompaniment) mediaPlayer?.setVolume(0f, vol) else mediaPlayer?.setVolume(vol, 0f)
                }
            } catch (e: Exception) {
                Log.w(TAG, "setVolume failed: ${e.message}")
            }
        } else {
            applyCurrentVolume()
        }
    }

    private fun applyCurrentVolume() {
        val vol = if (isMuted) 0f else currentVolume
        try {
            mediaPlayer?.setVolume(vol, vol)
        } catch (e: Exception) {
            Log.w(TAG, "applyCurrentVolume failed: ${e.message}")
        }
    }

    override fun release() {
        stop()
        activeSurface = null
    }

    override val isPlaying: Boolean
        get() = try {
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }

    override val currentPositionMs: Long
        get() = try {
            mediaPlayer?.currentPosition?.toLong() ?: requestedPositionMs
        } catch (_: Exception) {
            requestedPositionMs
        }

    override val durationMs: Long
        get() = try {
            mediaPlayer?.duration?.toLong()?.takeIf { it > 0 } ?: 0L
        } catch (_: Exception) {
            0L
        }
}
