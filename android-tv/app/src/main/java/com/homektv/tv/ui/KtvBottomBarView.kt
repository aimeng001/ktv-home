package com.homektv.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.homektv.tv.R
import com.homektv.tv.databinding.ViewKtvBottomBarBinding
import kotlin.math.roundToInt

internal fun calculatePlaybackProgress(positionMs: Long, durationMs: Long): Int {
    if (durationMs <= 0L || positionMs <= 0L) return 0
    if (positionMs >= durationMs) return 1000
    return ((positionMs.toDouble() / durationMs.toDouble()) * 1000.0).roundToInt().coerceIn(0, 1000)
}

/**
 * 点歌台底部常驻播控栏，支持当前歌曲状态呈现、快捷播控与气氛互动。
 *
 * Persistent bottom control bar for the KTV ordering kiosk.
 */
class KtvBottomBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    val binding = ViewKtvBottomBarBinding.inflate(LayoutInflater.from(context), this, true)

    var onPlayPauseClick: (() -> Unit)? = null
    var onNextClick: (() -> Unit)? = null
    var onRestartClick: (() -> Unit)? = null
    var onVocalToggleClick: (() -> Unit)? = null
    var onEffectClick: ((String) -> Unit)? = null
    var onQueueClick: (() -> Unit)? = null
    var onQrCodeClick: (() -> Unit)? = null

    init {
        binding.imgNowPlayingDisc.clipToOutline = true
        setupActions()
    }

    private fun setupActions() {
        binding.btnPlayPause.setOnClickListener { onPlayPauseClick?.invoke() }
        binding.btnNext.setOnClickListener { onNextClick?.invoke() }
        binding.btnRestart.setOnClickListener { onRestartClick?.invoke() }
        binding.btnVocalToggle.setOnClickListener { onVocalToggleClick?.invoke() }
        binding.btnEffectApplause.setOnClickListener { onEffectClick?.invoke("clap") }
        binding.btnEffectCheer.setOnClickListener { onEffectClick?.invoke("cheer") }
        binding.btnQueueBadge.setOnClickListener { onQueueClick?.invoke() }
        binding.btnQrCode.setOnClickListener { onQrCodeClick?.invoke() }
    }

    fun bindPlayback(
        title: String?,
        artist: String?,
        queueCount: Int,
        layout: String?,
        audioTracks: Int = 1,
        isPlaying: Boolean = true,
        vocalMode: String = "ACCOMPANIMENT",
        positionMs: Long = 0L,
        durationMs: Long = 0L,
    ) {
        if (!title.isNullOrBlank()) {
            val artistName = artist?.takeIf { it.isNotBlank() }
            binding.txtNowPlayingTitle.text = artistName?.let { "$title · $it" } ?: title
            binding.txtNowPlayingTitle.contentDescription = "$title · ${artistName ?: context.getString(R.string.unknown_artist)}"
            binding.txtNowPlayingArtist.text = ""
        } else {
            binding.txtNowPlayingTitle.text = context.getString(R.string.please_order_song)
            binding.txtNowPlayingTitle.contentDescription = context.getString(R.string.playback_none)
            binding.txtNowPlayingArtist.text = ""
        }

        binding.playbackProgress.progress = calculatePlaybackProgress(positionMs, durationMs)
        binding.btnPlayPause.text = context.getString(
            if (isPlaying) R.string.pause_action else R.string.play_action,
        )
        binding.btnPlayPause.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
            0,
            0,
            0,
        )
        binding.btnQueueBadge.text = context.getString(R.string.kiosk_queue_action)
        binding.btnQueueBadge.contentDescription = if (queueCount > 0) {
            context.getString(R.string.queue_badge_count, queueCount)
        } else {
            context.getString(R.string.queue_badge_initial)
        }

        val policy = VocalTogglePolicy.resolve(layout, audioTracks)
        binding.btnVocalToggle.isEnabled = policy.isEnabled
        binding.btnVocalToggle.alpha = if (policy.isEnabled) 1.0f else 0.45f
        binding.btnVocalToggle.text = context.getString(R.string.kiosk_vocal_action)

        binding.txtVocalBadge.text = context.getString(
            if (vocalMode.equals("ORIGINAL", ignoreCase = true)) R.string.vocal_original else R.string.vocal_backing,
        )
        binding.btnVocalToggle.contentDescription = "${policy.hint}，当前${binding.txtVocalBadge.text}"
        binding.txtVocalBadge.visibility = GONE
    }

    fun setArtistAvatar(bitmap: Bitmap?) {
        if (bitmap == null) {
            binding.imgNowPlayingDisc.setImageResource(R.drawable.vinyl_disc)
        } else {
            binding.imgNowPlayingDisc.setImageBitmap(bitmap)
        }
    }
}
