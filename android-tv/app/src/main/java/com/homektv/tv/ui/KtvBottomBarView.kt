package com.homektv.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.homektv.tv.databinding.ViewKtvBottomBarBinding

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

    init {
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
    }

    fun bindPlayback(
        title: String?,
        artist: String?,
        queueCount: Int,
        layout: String?,
        audioTracks: Int = 1,
        isPlaying: Boolean = true,
        vocalMode: String = "ACCOMPANIMENT",
    ) {
        if (!title.isNullOrBlank()) {
            binding.txtNowPlayingTitle.text = title
            binding.txtNowPlayingArtist.text = artist?.takeIf { it.isNotBlank() } ?: "未知歌手"
        } else {
            binding.txtNowPlayingTitle.text = "暂无播放"
            binding.txtNowPlayingArtist.text = "请先点歌"
        }

        binding.btnPlayPause.text = if (isPlaying) "暂停" else "播放"
        binding.btnQueueBadge.text = "已点 $queueCount 首"

        val policy = VocalTogglePolicy.resolve(layout, audioTracks)
        binding.btnVocalToggle.isEnabled = policy.isEnabled
        binding.btnVocalToggle.alpha = if (policy.isEnabled) 1.0f else 0.45f
        binding.btnVocalToggle.text = policy.hint

        binding.txtVocalBadge.text = if (vocalMode.equals("ORIGINAL", ignoreCase = true)) "原唱" else "伴唱"
        binding.txtVocalBadge.visibility = if (policy.isEnabled) VISIBLE else GONE
    }
}
