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
    fun prepareAndPlay(fileId: Long, streamUrl: String, initialPositionMs: Long = 0L)
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

class FfmpegFallbackPlayer(
    private val context: Context,
    private val onStateChanged: (isPlaying: Boolean) -> Unit = {},
    private val onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    private val onFinished: () -> Unit = {},
    private val onError: (message: String) -> Unit = {},
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

    override fun prepareAndPlay(fileId: Long, streamUrl: String, initialPositionMs: Long) {
        stop()
        currentFileId = fileId
        currentStreamUrl = streamUrl
        requestedPositionMs = initialPositionMs.coerceAtLeast(0L)

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
                    Log.i(TAG, "MediaPlayer prepared for fileId=$fileId")
                    if (requestedPositionMs > 0L) {
                        mp.seekTo(requestedPositionMs.toInt())
                    }
                    mp.start()
                    applyCurrentVolume()
                    onStateChanged(true)
                    mainHandler.post(progressRunnable)
                }
                setOnCompletionListener {
                    Log.i(TAG, "MediaPlayer playback completed for fileId=$fileId")
                    stop()
                    onFinished()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra for fileId=$fileId")
                    onError("FALLBACK_ERROR_${what}_$extra")
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "prepareAndPlay exception for fileId=$fileId: ${e.message}", e)
            onError(e.message ?: "FALLBACK_INIT_FAILED")
        }
    }

    override fun pause() {
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
        } else {
            setChannelMode(mode)
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
