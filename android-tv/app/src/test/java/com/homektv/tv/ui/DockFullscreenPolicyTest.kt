package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DockFullscreenPolicyTest {

    @Test
    fun videoClickActionEntersFullscreenWhenDockedAndVisible() {
        val action = DockFullscreenPolicy.videoClickAction(
            videoVisible = true,
            externalDisplayActive = false,
            isFullscreen = false,
            controlsVisible = false,
        )
        assertEquals(DockFullscreenPolicy.VideoClickAction.ENTER_FULLSCREEN, action)
    }

    @Test
    fun videoClickActionShowsControlsWhenFullscreenAndControlsHidden() {
        val action = DockFullscreenPolicy.videoClickAction(
            videoVisible = true,
            externalDisplayActive = false,
            isFullscreen = true,
            controlsVisible = false,
        )
        assertEquals(DockFullscreenPolicy.VideoClickAction.SHOW_CONTROLS, action)
    }

    @Test
    fun videoClickActionNeverExitsFullscreen() {
        val actionWithControls = DockFullscreenPolicy.videoClickAction(
            videoVisible = true,
            externalDisplayActive = false,
            isFullscreen = true,
            controlsVisible = true,
        )
        assertEquals(DockFullscreenPolicy.VideoClickAction.IGNORE, actionWithControls)
    }

    @Test
    fun videoClickActionIgnoredWhenExternalDisplayActiveOrVideoHidden() {
        assertEquals(
            DockFullscreenPolicy.VideoClickAction.IGNORE,
            DockFullscreenPolicy.videoClickAction(
                videoVisible = false,
                externalDisplayActive = false,
                isFullscreen = false,
                controlsVisible = false,
            )
        )
        assertEquals(
            DockFullscreenPolicy.VideoClickAction.IGNORE,
            DockFullscreenPolicy.videoClickAction(
                videoVisible = true,
                externalDisplayActive = true,
                isFullscreen = false,
                controlsVisible = false,
            )
        )
    }
}
