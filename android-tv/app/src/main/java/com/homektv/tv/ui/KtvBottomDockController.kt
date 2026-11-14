package com.homektv.tv.ui

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.homektv.tv.R
import com.homektv.tv.databinding.ViewKtvBottomDockBinding

data class BottomDockUiModel(
    val queueCount: Int,
    val isPlaying: Boolean,
    val vocalMode: String,
    val vocalSwitchEnabled: Boolean = true,
    val videoVisible: Boolean = false,
    val nowPlayingLabel: String = "",
    val queueCountKnown: Boolean = true,
    val queueSnapshotFresh: Boolean = true,
) {
    private val safeQueueCount: Int get() = queueCount.coerceAtLeast(0)

    val vocalLabel: String
        get() = if (vocalMode.equals("original", ignoreCase = true)) "原唱" else "伴唱"

    val vocalGlowActive: Boolean
        get() = vocalSwitchEnabled && vocalMode.equals("accompaniment", ignoreCase = true)

    val queueBadgeText: String
        get() = when {
            !queueCountKnown -> "已点队列状态尚未同步"
            !queueSnapshotFresh -> "已点 $safeQueueCount 首（上次同步）"
            else -> "已点 $safeQueueCount 首"
        }

    val queueCountText: String
        get() = safeQueueCount.toString()

    val playbackLabel: String
        get() = if (isPlaying) "暂停" else "播放"
}

class KtvBottomDockController(
    private val binding: ViewKtvBottomDockBinding,
    val playerContainer: FrameLayout,
    val playerView: PlayerView,
    private val txtNowPlaying: TextView? = null,
    private val imgQr: ImageView? = null,
    private val actions: Actions,
    private val onFullscreenChanged: (Boolean) -> Unit,
    private val onFullscreenClick: (() -> Unit)? = null,
) {
    data class Actions(
        val onHome: () -> Unit,
        val onQueue: () -> Unit,
        val onHistory: () -> Unit,
        val onCheer: () -> Unit,
        val onToggleVocal: () -> Unit,
        val onTogglePlayback: () -> Unit,
        val onRestart: () -> Unit,
        val onNext: () -> Unit,
        val onMixer: () -> Unit,
        val onQrCode: () -> Unit,
        val onDownloads: () -> Unit,
        val onPitchDown: () -> Unit,
        val onPitchUp: () -> Unit,
    )

    val videoContainer: FrameLayout get() = binding.videoContainer
    val root: View get() = binding.root

    var isFullscreen: Boolean = false
        private set

    var externalDisplayActive: Boolean = false
        private set

    private var videoVisible = false
    val isVideoVisible: Boolean get() = videoVisible
    private var cachedQrBitmap: Bitmap? = null
    private val iconCachePolicy = BottomDockIconCachePolicy()

    init {
        binding.btnDockHome.setOnClickListener { actions.onHome() }
        binding.btnDockQueue.setOnClickListener { actions.onQueue() }
        binding.btnDockHistory.setOnClickListener { actions.onHistory() }
        binding.btnDockCheer.setOnClickListener { actions.onCheer() }
        binding.btnDockVocal.setOnClickListener { actions.onToggleVocal() }
        binding.btnDockPlayPause.setOnClickListener { actions.onTogglePlayback() }
        binding.btnDockRestart.setOnClickListener { actions.onRestart() }
        binding.btnDockNext.setOnClickListener { actions.onNext() }
        binding.btnDockMixer.setOnClickListener { actions.onMixer() }
        binding.btnDockDownloads.setOnClickListener { actions.onDownloads() }
        binding.btnDockToneDown.setOnClickListener { actions.onPitchDown() }
        binding.btnDockToneUp.setOnClickListener { actions.onPitchUp() }
        binding.btnDockVolDown.setOnClickListener {
            val am = binding.root.context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            am?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
        }
        binding.btnDockVolUp.setOnClickListener {
            val am = binding.root.context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            am?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
        }
        val handleVideoClick = {
            if (isFullscreen) {
                onFullscreenClick?.invoke()
            } else {
                enterFullscreen()
            }
        }
        binding.videoContainer.setOnClickListener { handleVideoClick() }
        playerContainer.setOnClickListener { handleVideoClick() }
        imgQr?.setOnClickListener { actions.onQrCode() }
        playerView.useController = false
        playerView.isFocusable = false
        playerView.isClickable = false
        setVideoVisible(false)
        normalizeDockButtonIcons()
    }

    private fun normalizeDockButtonIcons() {
        val sizePx = (28 * binding.root.resources.displayMetrics.density).toInt()
        val buttons = listOf(
            binding.btnDockHome,
            binding.btnDockQueue,
            binding.btnDockHistory,
            binding.btnDockCheer,
            binding.btnDockVocal,
            binding.btnDockPlayPause,
            binding.btnDockRestart,
            binding.btnDockNext,
            binding.btnDockMixer,
            binding.btnDockVolDown,
            binding.btnDockVolUp,
            binding.btnDockToneDown,
            binding.btnDockToneUp,
            binding.btnDockDownloads,
        )
        buttons.forEach { btn ->
            val drawables = btn.compoundDrawables
            drawables.forEach { d -> d?.setBounds(0, 0, sizePx, sizePx) }
            btn.setCompoundDrawables(drawables[0], drawables[1], drawables[2], drawables[3])
        }
    }

    fun render(model: BottomDockUiModel) {
        val count = model.queueCount.coerceAtLeast(0)
        binding.txtDockQueueCount.text = model.queueCountText
        binding.txtDockQueueCount.contentDescription = model.queueBadgeText
        binding.txtDockQueueCount.visibility = View.VISIBLE
        binding.btnDockQueue.contentDescription = model.queueBadgeText

        binding.btnDockVocal.text = model.vocalLabel
        val vocalIconRes = if (model.vocalLabel == "伴唱") R.drawable.ic_dock_accompany else R.drawable.ic_dock_original
        val vocalIconChanged = iconCachePolicy.shouldUpdateVocalIcon(vocalIconRes)
        if (vocalIconChanged) {
            binding.btnDockVocal.setCompoundDrawablesWithIntrinsicBounds(0, vocalIconRes, 0, 0)
        }
        binding.btnDockVocal.isSelected = model.vocalGlowActive
        binding.btnDockVocal.isEnabled = model.vocalSwitchEnabled
        binding.btnDockVocal.alpha = if (model.vocalSwitchEnabled) 1f else 0.45f
        binding.btnDockVocal.contentDescription = "切换原唱或伴唱，当前${model.vocalLabel}"

        binding.btnDockPlayPause.text = model.playbackLabel
        val playPauseIconRes = if (model.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
        val playPauseIconChanged = iconCachePolicy.shouldUpdatePlayPauseIcon(playPauseIconRes)
        if (playPauseIconChanged) {
            binding.btnDockPlayPause.setCompoundDrawablesRelativeWithIntrinsicBounds(0, playPauseIconRes, 0, 0)
        }
        if (vocalIconChanged || playPauseIconChanged) {
            normalizeDockButtonIcons()
        }
        binding.btnDockPlayPause.contentDescription = if (model.isPlaying) "暂停播放" else "继续播放"

        txtNowPlaying?.text = model.nowPlayingLabel
        txtNowPlaying?.visibility = View.GONE
        setVideoVisible(model.videoVisible)
    }

    fun setQrBitmap(bitmap: Bitmap?) {
        cachedQrBitmap = bitmap
        imgQr?.setImageBitmap(bitmap)
        imgQr?.visibility = View.GONE
        updateContainerVisibility()
    }

    fun setVideoVisible(visible: Boolean) {
        videoVisible = visible
        updateContainerVisibility()
    }

    private fun updateContainerVisibility() {
        if (!isFullscreen) {
            val effectiveVideoVisible = videoVisible && !externalDisplayActive
            val frameState = BottomDockVideoFramePolicy.resolve(effectiveVideoVisible)
            playerView.visibility = if (effectiveVideoVisible) View.VISIBLE else View.INVISIBLE
            val shouldShowContainer = effectiveVideoVisible
            playerContainer.visibility = if (shouldShowContainer) View.VISIBLE else View.INVISIBLE
            playerContainer.setBackgroundResource(
                if (effectiveVideoVisible) R.drawable.bg_ktv_dock_video_frame else android.R.color.transparent,
            )
            binding.videoContainer.visibility = if (frameState.containerVisible) View.VISIBLE else View.INVISIBLE
            binding.videoContainer.setBackgroundResource(
                if (frameState.useIdleFrame) R.drawable.bg_ktv_dock_video_frame_idle else R.drawable.bg_ktv_dock_video_frame,
            )
            val shadowVisibility = if (frameState.showEdgeShadows) View.VISIBLE else View.INVISIBLE
            binding.videoShadowLeft.visibility = shadowVisibility
            binding.videoShadowRight.visibility = shadowVisibility
            binding.innerVideoShadowLeft.visibility = shadowVisibility
            binding.innerVideoShadowRight.visibility = shadowVisibility
        }
    }

    fun setExternalDisplayActive(active: Boolean) {
        externalDisplayActive = active
        if (DockFullscreenPolicy.shouldExitOnExternalDisplay(active, isFullscreen)) {
            exitFullscreen()
        }
        updateContainerVisibility()
    }

    fun ensurePlayerInDock() {
        if (isFullscreen) {
            exitFullscreen()
        }
    }

    fun focusVideo() {
        binding.videoContainer.requestFocus()
    }

    fun focusHome() {
        binding.btnDockHome.requestFocus()
    }

    fun focusMixer() {
        binding.btnDockMixer.requestFocus()
    }

    fun updateFocusUpTargets(isDashboard: Boolean) {
        val browserLeftTarget = if (isDashboard) R.id.tileTikTok else R.id.browserResultsRecyclerView
        val browserCenterTarget = if (isDashboard) R.id.tileSongName else R.id.browserResultsRecyclerView
        val browserRightTarget = if (isDashboard) R.id.tileLocal else R.id.browserResultsRecyclerView

        binding.btnDockPlayPause.nextFocusUpId = browserLeftTarget
        binding.btnDockVocal.nextFocusUpId = browserLeftTarget
        binding.btnDockRestart.nextFocusUpId = browserLeftTarget
        binding.btnDockNext.nextFocusUpId = browserCenterTarget
        binding.btnDockVolDown.nextFocusUpId = browserCenterTarget
        binding.videoContainer.nextFocusUpId = browserCenterTarget
        binding.btnDockVolUp.nextFocusUpId = if (isDashboard) R.id.tileTheme else browserRightTarget
        binding.btnDockToneDown.nextFocusUpId = if (isDashboard) R.id.tileFavorites else browserRightTarget
        binding.btnDockToneUp.nextFocusUpId = if (isDashboard) R.id.tileFavorites else browserRightTarget
        binding.btnDockDownloads.nextFocusUpId = browserRightTarget
        binding.btnDockQueue.nextFocusUpId = browserRightTarget
    }

    fun toggleFullscreen() {
        if (!videoVisible || externalDisplayActive) return
        if (isFullscreen) {
            onFullscreenClick?.invoke()
        } else {
            enterFullscreen()
        }
    }

    fun enterFullscreen(): Boolean {
        if (!DockFullscreenPolicy.canEnterFullscreen(videoVisible, externalDisplayActive, isFullscreen)) return false
        isFullscreen = true
        applyWindowBounds(DockFullscreenPolicy.boundsFor(true))
        txtNowPlaying?.visibility = View.GONE
        imgQr?.visibility = View.GONE
        onFullscreenChanged(true)
        return true
    }

    fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        applyWindowBounds(DockFullscreenPolicy.boundsFor(false))
        txtNowPlaying?.visibility = View.GONE
        imgQr?.visibility = View.GONE
        onFullscreenChanged(false)
        binding.videoContainer.requestFocus()
    }

    fun destroy() {
        binding.btnDockHome.setOnClickListener(null)
        binding.btnDockQueue.setOnClickListener(null)
        binding.btnDockHistory.setOnClickListener(null)
        binding.btnDockCheer.setOnClickListener(null)
        binding.btnDockVocal.setOnClickListener(null)
        binding.btnDockPlayPause.setOnClickListener(null)
        binding.btnDockRestart.setOnClickListener(null)
        binding.btnDockNext.setOnClickListener(null)
        binding.btnDockMixer.setOnClickListener(null)
        binding.btnDockVolDown.setOnClickListener(null)
        binding.btnDockVolUp.setOnClickListener(null)
        binding.btnDockDownloads.setOnClickListener(null)
        binding.btnDockToneDown.setOnClickListener(null)
        binding.btnDockToneUp.setOnClickListener(null)
        binding.videoContainer.setOnClickListener(null)
        playerContainer.setOnClickListener(null)
        imgQr?.setOnClickListener(null)
    }

    private fun applyWindowBounds(bounds: DockWindowBounds) {
        val lp = playerContainer.layoutParams as? FrameLayout.LayoutParams ?: return
        if (bounds.isMatchParent) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
            lp.bottomMargin = 0
            playerContainer.background = null
        } else {
            val density = playerContainer.resources.displayMetrics.density
            lp.width = (bounds.widthDp * density).toInt()
            lp.height = (bounds.heightDp * density).toInt()
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (bounds.bottomMarginDp * density).toInt()
            playerContainer.layoutParams = lp
            updateContainerVisibility()
        }
    }
}

class MarqueeTextView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : androidx.appcompat.widget.AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        isSelected = true
    }

    override fun isFocused(): Boolean = true

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (hasWindowFocus) super.onWindowFocusChanged(true)
    }
}
