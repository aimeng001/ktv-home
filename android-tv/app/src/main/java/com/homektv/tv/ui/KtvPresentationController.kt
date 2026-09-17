package com.homektv.tv.ui

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView

/**
 * Owns the Android Presentation lifecycle for an HDMI/secondary display.
 * The playback engine remains the single source of media state; this class only
 * switches its video surface and never writes to the external library.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class KtvPresentationController(
    private val activity: Activity,
    private val onPlayerViewChanged: (PlayerView?) -> Unit,
) : DisplayManager.DisplayListener {
    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var presentation: VideoPresentation? = null
    private var activeDisplayId: Int? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        displayManager.registerDisplayListener(this, mainHandler)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        displayManager.unregisterDisplayListener(this)
        detachPresentation()
    }

    override fun onDisplayAdded(displayId: Int) = refresh()

    override fun onDisplayRemoved(displayId: Int) = refresh()

    override fun onDisplayChanged(displayId: Int) = refresh()

    private fun refresh() {
        if (!started || activity.isFinishing || activity.isDestroyed) return
        val display = KtvExternalDisplayPolicy.choose(
            displayManager.displays.map { value ->
                KtvExternalDisplayPolicy.DisplayCandidate(
                    id = value.displayId,
                    isPresentation = value.flags and Display.FLAG_PRESENTATION != 0,
                    isOff = value.state == Display.STATE_OFF,
                )
            },
        )?.let { chosen -> displayManager.getDisplay(chosen.id) }
        if (display?.displayId == activeDisplayId && presentation?.isShowing == true) return

        detachPresentation()
        if (display == null) return

        val next = VideoPresentation(activity, display)
        next.setOnDismissListener { _ ->
            if (presentation === next) {
                activeDisplayId = null
                presentation = null
                onPlayerViewChanged(null)
            }
        }
        presentation = next
        activeDisplayId = display.displayId
        runCatching { next.show() }
            .onSuccess {
                onPlayerViewChanged(next.playerView)
            }
            .onFailure {
                activeDisplayId = null
                presentation = null
                onPlayerViewChanged(null)
            }
    }

    private fun detachPresentation() {
        onPlayerViewChanged(null)
        presentation?.setOnDismissListener(null)
        presentation?.dismiss()
        presentation = null
        activeDisplayId = null
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private class VideoPresentation(context: Context, display: Display) : Presentation(context, display) {
        val playerView = PlayerView(context).apply {
            useController = false
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(playerView)
            })
        }
    }
}
