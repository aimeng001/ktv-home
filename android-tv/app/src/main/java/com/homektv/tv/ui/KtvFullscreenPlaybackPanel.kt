package com.homektv.tv.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.homektv.tv.R

/** Full-screen MV controls hosted above the existing shared PlayerView. */
internal class KtvFullscreenPlaybackPanel(
    private val activity: Activity,
    private val root: View,
    private val onAction: (FullscreenPlaybackAction) -> Unit,
    private val onSeekBy: (Long) -> Unit,
    private val onFocusReturn: () -> Unit,
    private val requestExitFullscreen: () -> Unit,
) {
    private val overlay = root.findViewById<FrameLayout>(R.id.fullscreenPlaybackOverlayRoot)
    private val nowPlayingBar = root.findViewById<LinearLayout>(R.id.fullscreenNowPlayingBar)
    private val nowPlayingText = root.findViewById<TextView>(R.id.txtFullscreenNowPlaying)
    private val controls = root.findViewById<LinearLayout>(R.id.fullscreenControlsPanel)
    private val progressRow = root.findViewById<LinearLayout>(R.id.fullscreenProgressRow)
    private val actionRow = root.findViewById<LinearLayout>(R.id.fullscreenActionRow)
    private val rewindButton = root.findViewById<ImageView>(R.id.btnFullscreenRewind)
    private val forwardButton = root.findViewById<ImageView>(R.id.btnFullscreenForward)
    private val pitchButton = root.findViewById<ImageView>(R.id.btnFullscreenPitch)
    private val currentTime = root.findViewById<TextView>(R.id.txtFullscreenCurrentTime)
    private val durationText = root.findViewById<TextView>(R.id.txtFullscreenDuration)
    private val progressBar = root.findViewById<SeekBar>(R.id.seekFullscreenProgress)
    private val actionButtons = mutableListOf<ActionButton>()
    private val keyRouter = FullscreenPlaybackPanelKeyRouter()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoHide = Runnable { hideControls() }
    private var fullscreen = false
    private var controlsVisible = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var songTitle = ""
    private var playing = false
    private var vocalMode = "accompaniment"
    private var muted = false
    private var seekAvailable = false
    private var suspended = false
    private var metrics = FullscreenPlaybackPanelPolicy.metrics(1920, 1080)

    init {
        createActionButtons()
        configureMetrics()
        overlay.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left > 0 && bottom - top > 0 &&
                (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
            ) {
                configureMetrics(right - left, bottom - top)
                render()
            }
        }
        overlay.setOnClickListener {
            if (fullscreen && !controlsVisible) {
                showControls(FullscreenPanelFocusTarget.PAUSE_PLAY)
            }
        }
        rewindButton.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        forwardButton.setOnClickListener { seekBy(SEEK_STEP_MS) }
        pitchButton.setOnClickListener {
            onAction(FullscreenPlaybackAction.PITCH)
            resetAutoHide()
        }
        progressBar.setOnTouchListener { _, _ -> true }
    }

    fun showControlsFromClick() {
        if (fullscreen && !controlsVisible) {
            showControls(FullscreenPanelFocusTarget.PAUSE_PLAY)
        }
    }

    fun setFullscreen(active: Boolean) {
        fullscreen = active
        handler.removeCallbacks(autoHide)
        keyRouter.reset()
        controlsVisible = active
        overlay.isClickable = active
        overlay.visibility = if (active) View.VISIBLE else View.GONE
        controls.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            render()
            focus(FullscreenPanelFocusTarget.PAUSE_PLAY)
            scheduleAutoHide()
        } else {
            suspended = false
        }
    }

    fun updatePlayback(
        title: String?,
        position: Long,
        duration: Long,
        isPlaying: Boolean,
        currentVocalMode: String,
        isMuted: Boolean,
        canSeek: Boolean,
    ) {
        songTitle = title.orEmpty()
        positionMs = position.coerceAtLeast(0L)
        durationMs = duration.coerceAtLeast(0L)
        playing = isPlaying
        vocalMode = currentVocalMode
        muted = isMuted
        seekAvailable = canSeek && durationMs > 0L
        render()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!fullscreen) return false
        val key = event.keyCode.toPanelKey() ?: return false
        val action = if (event.action == KeyEvent.ACTION_DOWN) {
            FullscreenPanelKeyAction.DOWN
        } else {
            FullscreenPanelKeyAction.UP
        }
        val decision = keyRouter.route(fullscreen, controlsVisible, key, action, event.repeatCount)
        when (decision.effect) {
            FullscreenPanelKeyEffect.PASS -> return false
            FullscreenPanelKeyEffect.CONSUME -> Unit
            FullscreenPanelKeyEffect.SHOW_CONTROLS -> {
                showControls(decision.focusTarget ?: FullscreenPanelFocusTarget.PAUSE_PLAY)
            }
            FullscreenPanelKeyEffect.HIDE_CONTROLS -> hideControls()
            FullscreenPanelKeyEffect.EXIT_FULLSCREEN -> {
                hideControls()
                onExitFullscreen()
            }
            FullscreenPanelKeyEffect.MOVE_FOCUS -> {
                if (action == FullscreenPanelKeyAction.DOWN) moveFocus(decision.direction)
                resetAutoHide()
            }
            FullscreenPanelKeyEffect.CONFIRM -> {
                if (action == FullscreenPanelKeyAction.UP) currentFocusedButton()?.performClick()
                resetAutoHide()
            }
            FullscreenPanelKeyEffect.RESET_TIMEOUT -> resetAutoHide()
        }
        return true
    }

    fun suspendAutoHide() {
        suspended = true
        handler.removeCallbacks(autoHide)
    }

    fun resumeAutoHide() {
        suspended = false
        if (fullscreen && controlsVisible && currentFocusedButton() == null) {
            focus(FullscreenPanelFocusTarget.PAUSE_PLAY)
        }
        resetAutoHide()
    }

    fun onStop() {
        handler.removeCallbacks(autoHide)
    }

    fun onStart() {
        if (fullscreen && controlsVisible) resetAutoHide()
    }

    fun destroy() {
        handler.removeCallbacks(autoHide)
        actionButtons.forEach { it.root.setOnClickListener(null) }
        rewindButton.setOnClickListener(null)
        forwardButton.setOnClickListener(null)
        pitchButton.setOnClickListener(null)
    }

    private fun onExitFullscreen() {
        if (fullscreen) requestExitFullscreen()
    }

    private fun createActionButtons() {
        val definitions = listOf(
            ActionSpec(FullscreenPlaybackAction.PAUSE_PLAY, R.id.btnFullscreenPause, R.drawable.ktv_ref_v50_player_pause, "暂停"),
            ActionSpec(FullscreenPlaybackAction.VOCAL, R.id.btnFullscreenVocal, R.drawable.ktv_ref_v50_player_vocal, "伴唱"),
            ActionSpec(FullscreenPlaybackAction.RESTART, R.id.btnFullscreenRestart, R.drawable.ktv_ref_v50_player_restart, "重唱"),
            ActionSpec(FullscreenPlaybackAction.NEXT, R.id.btnFullscreenNext, R.drawable.ktv_ref_v50_player_next, "切歌"),
            ActionSpec(FullscreenPlaybackAction.VOLUME_DOWN, R.id.btnFullscreenVolumeDown, R.drawable.ktv_ref_v50_player_volume_down, "音量减"),
            ActionSpec(FullscreenPlaybackAction.VOLUME_UP, R.id.btnFullscreenVolumeUp, R.drawable.ktv_ref_v50_player_volume_up, "音量加"),
            ActionSpec(FullscreenPlaybackAction.MUTE, R.id.btnFullscreenMute, R.drawable.ktv_ref_v50_player_mute, "静音"),
            ActionSpec(FullscreenPlaybackAction.KIOSK, R.id.btnFullscreenKiosk, R.drawable.ktv_ref_v50_player_kiosk, "点歌台"),
            ActionSpec(FullscreenPlaybackAction.QUEUE, R.id.btnFullscreenQueue, R.drawable.ktv_ref_v50_player_queue, "已点"),
            ActionSpec(FullscreenPlaybackAction.HOME, R.id.btnFullscreenHome, R.drawable.ktv_ref_v50_player_home, "首页"),
        )
        definitions.forEach { spec ->
            val item = FrameLayout(activity).apply {
                id = spec.id
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                contentDescription = spec.label
                background = null
                setOnFocusChangeListener { view, focused ->
                    view.findViewById<View>(R.id.fullscreenActionCircle)?.isSelected = focused
                    if (focused) resetAutoHide()
                }
                setOnClickListener {
                    onAction(spec.action)
                    resetAutoHide()
                }
            }
            val circle = ImageView(activity).apply {
                id = R.id.fullscreenActionCircle
                background = activity.getDrawable(R.drawable.ktv_fullscreen_focus_circle)
                isFocusable = false
                isClickable = false
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(spec.icon)
            }
            val label = TextView(activity).apply {
                id = R.id.fullscreenActionLabel
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(android.graphics.Color.WHITE)
                typeface = Typeface.DEFAULT
                text = spec.label
                isFocusable = false
            }
            item.addView(circle, FrameLayout.LayoutParams(70, 70, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = 10
            })
            item.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28, Gravity.BOTTOM).apply {
                bottomMargin = 8
            })
            actionRow.addView(item, LinearLayout.LayoutParams(140, 140))
            actionButtons += ActionButton(spec, item, circle, label)
        }
        rewindButton.isFocusable = true
        forwardButton.isFocusable = true
        pitchButton.isFocusable = true
        rewindButton.isSelected = false
        forwardButton.isSelected = false
        pitchButton.isSelected = false
        listOf(rewindButton, forwardButton, pitchButton).forEach { button ->
            button.setOnFocusChangeListener { view, focused ->
                view.isSelected = focused
                if (focused) resetAutoHide()
            }
        }
        actionButtons.forEachIndexed { index, item ->
            item.root.nextFocusLeftId = actionButtons.getOrNull(index - 1)?.spec?.id ?: item.spec.id
            item.root.nextFocusRightId = actionButtons.getOrNull(index + 1)?.spec?.id ?: item.spec.id
            item.root.nextFocusUpId = if (index < actionButtons.size / 2) R.id.btnFullscreenRewind else R.id.btnFullscreenForward
        }
        rewindButton.nextFocusDownId = actionButtons.first().spec.id
        rewindButton.nextFocusRightId = R.id.btnFullscreenForward
        forwardButton.nextFocusDownId = actionButtons.last().spec.id
        forwardButton.nextFocusLeftId = R.id.btnFullscreenRewind
        forwardButton.nextFocusRightId = R.id.btnFullscreenPitch
        pitchButton.nextFocusDownId = actionButtons.last().spec.id
        pitchButton.nextFocusLeftId = R.id.btnFullscreenForward
    }

    private fun configureMetrics() {
        val display = activity.resources.displayMetrics
        configureMetrics(display.widthPixels, display.heightPixels)
    }

    private fun configureMetrics(widthPx: Int, heightPx: Int) {
        metrics = FullscreenPlaybackPanelPolicy.metrics(widthPx, heightPx)
        nowPlayingBar.layoutParams = (nowPlayingBar.layoutParams as FrameLayout.LayoutParams).apply {
            height = metrics.progressRowHeightPx + px(46)
        }
        nowPlayingText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(35).toFloat())
        nowPlayingText.setPadding(px(12), 0, px(10), 0)
        controls.layoutParams = (controls.layoutParams as FrameLayout.LayoutParams).apply {
            height = metrics.panelHeightPx
        }
        progressRow.layoutParams = (progressRow.layoutParams as LinearLayout.LayoutParams).apply {
            height = metrics.progressRowHeightPx
            marginStart = metrics.progressHorizontalMarginPx
            marginEnd = metrics.progressHorizontalMarginPx
        }
        val timeWidth = px(100)
        currentTime.layoutParams = (currentTime.layoutParams as LinearLayout.LayoutParams).apply { width = timeWidth }
        durationText.layoutParams = (durationText.layoutParams as LinearLayout.LayoutParams).apply { width = timeWidth }
        listOf(currentTime, durationText).forEach {
            it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.timeTextPx.toFloat())
        }
        progressBar.layoutParams = (progressBar.layoutParams as LinearLayout.LayoutParams).apply {
            height = metrics.progressButtonSizePx
        }
        progressBar.setPadding(px(30), progressBar.paddingTop, px(30), progressBar.paddingBottom)
        progressBar.thumbOffset = metrics.progressButtonSizePx / 2
        listOf(rewindButton, forwardButton, pitchButton).forEach { button ->
            button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
                width = metrics.progressButtonSizePx
                height = metrics.progressButtonSizePx
                if (button === pitchButton) marginStart = px(10)
            }
            button.setPadding(px(8), px(8), px(8), px(8))
        }
        actionRow.layoutParams = (actionRow.layoutParams as LinearLayout.LayoutParams).apply {
            width = metrics.actionGroupWidthPx
            height = metrics.actionRowHeightPx
        }
        actionButtons.forEach { item ->
            item.root.layoutParams = LinearLayout.LayoutParams(metrics.actionItemWidthPx, metrics.actionRowHeightPx)
            item.circle.layoutParams = (item.circle.layoutParams as FrameLayout.LayoutParams).apply {
                width = metrics.focusCircleSizePx
                height = metrics.focusCircleSizePx
                topMargin = px(10)
            }
            val icon = px(50)
            item.circle.setPadding((metrics.focusCircleSizePx - icon) / 2, (metrics.focusCircleSizePx - icon) / 2,
                (metrics.focusCircleSizePx - icon) / 2, (metrics.focusCircleSizePx - icon) / 2)
            item.label.layoutParams = (item.label.layoutParams as FrameLayout.LayoutParams).apply {
                height = px(28)
                bottomMargin = px(8)
            }
            item.label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.actionLabelTextPx.toFloat())
        }
    }

    private fun render() {
        nowPlayingBar.visibility = if (songTitle.isBlank()) View.GONE else View.VISIBLE
        nowPlayingText.text = if (songTitle.isBlank()) "" else "正在播放：$songTitle"
        nowPlayingText.isSelected = true
        currentTime.text = FullscreenPlaybackPanelPolicy.formatTime(positionMs)
        durationText.text = if (durationMs > 0) FullscreenPlaybackPanelPolicy.formatTime(durationMs) else "--:--"
        progressBar.progress = FullscreenPlaybackPanelPolicy.progressPermille(positionMs, durationMs)
        val pause = actionButtons.getOrNull(0) ?: return
        pause.circle.setImageResource(if (playing) R.drawable.ktv_ref_v50_player_pause else R.drawable.ktv_ref_v50_player_play)
        pause.label.text = if (playing) "暂停" else "播放"
        pause.root.contentDescription = pause.label.text
        val vocal = actionButtons[1]
        vocal.label.text = if (vocalMode.equals("original", true)) "原唱" else "伴唱"
        vocal.root.contentDescription = vocal.label.text
        val mute = actionButtons[6]
        mute.circle.setImageResource(if (muted) R.drawable.ktv_ref_v50_player_unmute else R.drawable.ktv_ref_v50_player_mute)
        mute.label.text = if (muted) "取消静音" else "静音"
        mute.root.contentDescription = mute.label.text
    }

    private fun showControls(target: FullscreenPanelFocusTarget) {
        controlsVisible = true
        controls.visibility = View.VISIBLE
        render()
        focus(target)
        resetAutoHide()
    }

    private fun hideControls() {
        handler.removeCallbacks(autoHide)
        keyRouter.reset()
        controlsVisible = false
        controls.visibility = View.GONE
        onFocusReturn()
    }

    private fun focus(target: FullscreenPanelFocusTarget) {
        when (target) {
            FullscreenPanelFocusTarget.PAUSE_PLAY -> actionButtons.firstOrNull()?.root?.requestFocus()
            FullscreenPanelFocusTarget.REWIND -> rewindButton.requestFocus()
            FullscreenPanelFocusTarget.FORWARD -> forwardButton.requestFocus()
        }
    }

    private fun moveFocus(direction: FullscreenPanelDirection?) {
        val focused = currentFocusedButton() ?: actionButtons.firstOrNull()?.root ?: return
        when (direction) {
            FullscreenPanelDirection.LEFT, FullscreenPanelDirection.RIGHT -> {
                if (focused === rewindButton || focused === forwardButton || focused === pitchButton) {
                    when {
                        focused === rewindButton && direction == FullscreenPanelDirection.RIGHT -> forwardButton.requestFocus()
                        focused === forwardButton && direction == FullscreenPanelDirection.LEFT -> rewindButton.requestFocus()
                        focused === forwardButton && direction == FullscreenPanelDirection.RIGHT -> pitchButton.requestFocus()
                        focused === pitchButton && direction == FullscreenPanelDirection.LEFT -> forwardButton.requestFocus()
                    }
                    return
                }
                val index = actionButtons.indexOfFirst { it.root === focused }.coerceAtLeast(0)
                val next = index + if (direction == FullscreenPanelDirection.LEFT) -1 else 1
                actionButtons.getOrNull(next.coerceIn(0, actionButtons.lastIndex))?.root?.requestFocus()
            }
            FullscreenPanelDirection.UP -> {
                val index = actionButtons.indexOfFirst { it.root === focused }
                if (index >= 0) focus(if (index < actionButtons.size / 2) FullscreenPanelFocusTarget.REWIND else FullscreenPanelFocusTarget.FORWARD)
            }
            FullscreenPanelDirection.DOWN -> {
                when {
                    focused === rewindButton -> actionButtons.firstOrNull()?.root?.requestFocus()
                    focused === forwardButton || focused === pitchButton -> actionButtons.lastOrNull()?.root?.requestFocus()
                }
            }
            null -> Unit
        }
    }

    private fun currentFocusedButton(): View? =
        actionButtons.firstOrNull { it.root.hasFocus() }?.root
            ?: rewindButton.takeIf { it.hasFocus() }
            ?: forwardButton.takeIf { it.hasFocus() }
            ?: pitchButton.takeIf { it.hasFocus() }

    private fun seekBy(deltaMs: Long) {
        if (!seekAvailable || durationMs <= 0L) return
        val target = (positionMs + deltaMs).coerceIn(0L, durationMs)
        if (target != positionMs) onSeekBy(target)
        resetAutoHide()
    }

    private fun resetAutoHide() {
        if (!fullscreen || !controlsVisible || suspended) return
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, FullscreenPlaybackPanelPolicy.AUTO_HIDE_DELAY_MS)
    }

    private fun scheduleAutoHide() = resetAutoHide()

    private fun px(value: Int) = (value * metrics.scale).toInt().coerceAtLeast(1)

    private fun Int.toPanelKey(): FullscreenPanelKey? = when (this) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> FullscreenPanelKey.BACK
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_SELECT -> FullscreenPanelKey.CENTER
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> FullscreenPanelKey.ENTER
        KeyEvent.KEYCODE_MENU -> FullscreenPanelKey.MENU
        KeyEvent.KEYCODE_DPAD_UP -> FullscreenPanelKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> FullscreenPanelKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> FullscreenPanelKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> FullscreenPanelKey.RIGHT
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> FullscreenPanelKey.VOLUME
        else -> null
    }

    private data class ActionSpec(
        val action: FullscreenPlaybackAction,
        val id: Int,
        val icon: Int,
        val label: String,
    )

    private data class ActionButton(
        val spec: ActionSpec,
        val root: FrameLayout,
        val circle: ImageView,
        val label: TextView,
    )

    companion object {
        private const val SEEK_STEP_MS = 10_000L
    }
}
