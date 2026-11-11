package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FullscreenPlaybackPanelPolicyTest {
    @Test
    fun firstCenterOnlyRevealsAndNextCenterConfirmsOnce() {
        val router = FullscreenPlaybackPanelKeyRouter()

        assertEquals(
            FullscreenPanelKeyEffect.SHOW_CONTROLS,
            router.route(true, false, FullscreenPanelKey.CENTER, FullscreenPanelKeyAction.DOWN).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.CONSUME,
            router.route(true, true, FullscreenPanelKey.CENTER, FullscreenPanelKeyAction.UP).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.CONSUME,
            router.route(true, true, FullscreenPanelKey.CENTER, FullscreenPanelKeyAction.DOWN).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.CONFIRM,
            router.route(true, true, FullscreenPanelKey.CENTER, FullscreenPanelKeyAction.UP).effect,
        )
    }

    @Test
    fun revealKeyRepeatsAndItsReleaseNeverConfirmOrMoveFocus() {
        val router = FullscreenPlaybackPanelKeyRouter()
        assertEquals(
            FullscreenPanelKeyEffect.SHOW_CONTROLS,
            router.route(true, false, FullscreenPanelKey.CENTER, FullscreenPanelKeyAction.DOWN).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.CONSUME,
            router.route(true, true, FullscreenPanelKey.CENTER, FullscreenPanelKeyAction.DOWN, repeatCount = 1).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.CONSUME,
            router.route(true, true, FullscreenPanelKey.CENTER, FullscreenPanelKeyAction.UP).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.CONSUME,
            router.route(true, true, FullscreenPanelKey.CENTER, FullscreenPanelKeyAction.DOWN).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.CONFIRM,
            router.route(true, true, FullscreenPanelKey.CENTER, FullscreenPanelKeyAction.UP).effect,
        )

        assertEquals(
            FullscreenPanelKeyEffect.SHOW_CONTROLS,
            router.route(true, false, FullscreenPanelKey.LEFT, FullscreenPanelKeyAction.DOWN).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.CONSUME,
            router.route(true, true, FullscreenPanelKey.LEFT, FullscreenPanelKeyAction.DOWN, repeatCount = 1).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.CONSUME,
            router.route(true, true, FullscreenPanelKey.LEFT, FullscreenPanelKeyAction.UP).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.MOVE_FOCUS,
            router.route(true, true, FullscreenPanelKey.LEFT, FullscreenPanelKeyAction.DOWN).effect,
        )
    }

    @Test
    fun backHidesControlsBeforeItExitsFullscreen() {
        val router = FullscreenPlaybackPanelKeyRouter()

        assertEquals(
            FullscreenPanelKeyEffect.CONSUME,
            router.route(true, true, FullscreenPanelKey.BACK, FullscreenPanelKeyAction.DOWN).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.HIDE_CONTROLS,
            router.route(true, true, FullscreenPanelKey.BACK, FullscreenPanelKeyAction.UP).effect,
        )
        assertEquals(
            FullscreenPanelKeyEffect.EXIT_FULLSCREEN,
            router.route(true, false, FullscreenPanelKey.BACK, FullscreenPanelKeyAction.UP).effect,
        )
    }

    @Test
    fun hiddenDirectionRevealsAtUsefulFocusAndVisibleDirectionStaysInPanel() {
        val router = FullscreenPlaybackPanelKeyRouter()
        val reveal = router.route(true, false, FullscreenPanelKey.LEFT, FullscreenPanelKeyAction.DOWN)
        assertEquals(FullscreenPanelKeyEffect.SHOW_CONTROLS, reveal.effect)
        assertEquals(FullscreenPanelFocusTarget.REWIND, reveal.focusTarget)

        val move = router.route(true, true, FullscreenPanelKey.RIGHT, FullscreenPanelKeyAction.DOWN)
        assertEquals(FullscreenPanelKeyEffect.MOVE_FOCUS, move.effect)
        assertEquals(FullscreenPanelDirection.RIGHT, move.direction)
        assertEquals(FullscreenPanelKeyEffect.PASS,
            router.route(true, false, FullscreenPanelKey.VOLUME, FullscreenPanelKeyAction.DOWN).effect)
    }

    @Test
    fun menuRevealReleaseDoesNotLeaveAStalePressedKey() {
        val router = FullscreenPlaybackPanelKeyRouter()
        assertEquals(FullscreenPanelKeyEffect.SHOW_CONTROLS,
            router.route(true, false, FullscreenPanelKey.MENU, FullscreenPanelKeyAction.DOWN).effect)
        assertEquals(FullscreenPanelKeyEffect.CONSUME,
            router.route(true, true, FullscreenPanelKey.MENU, FullscreenPanelKeyAction.UP).effect)
        assertEquals(FullscreenPanelKeyEffect.RESET_TIMEOUT,
            router.route(true, true, FullscreenPanelKey.MENU, FullscreenPanelKeyAction.DOWN).effect)
    }

    @Test
    fun metricsMatch1080pReferenceAndScaleFor720p() {
        val fhd = FullscreenPlaybackPanelPolicy.metrics(1920, 1080)
        assertEquals(210, fhd.panelHeightPx)
        assertEquals(70, fhd.progressRowHeightPx)
        assertEquals(140, fhd.actionRowHeightPx)
        assertEquals(1_400, fhd.actionGroupWidthPx)
        assertEquals(70, fhd.focusCircleSizePx)
        assertEquals(50, fhd.actionIconSizePx)
        assertEquals(21, fhd.actionLabelTextPx)
        assertEquals(26, fhd.timeTextPx)
        assertEquals(5, fhd.progressTrackHeightPx)
        assertEquals(5_000L, FullscreenPlaybackPanelPolicy.AUTO_HIDE_DELAY_MS)
        assertEquals(10, FullscreenPlaybackPanelPolicy.ACTION_COUNT)
        assertEquals(
            listOf(
                FullscreenPlaybackAction.PAUSE_PLAY,
                FullscreenPlaybackAction.VOCAL,
                FullscreenPlaybackAction.RESTART,
                FullscreenPlaybackAction.NEXT,
                FullscreenPlaybackAction.VOLUME_DOWN,
                FullscreenPlaybackAction.VOLUME_UP,
                FullscreenPlaybackAction.MUTE,
                FullscreenPlaybackAction.KIOSK,
                FullscreenPlaybackAction.QUEUE,
                FullscreenPlaybackAction.HOME,
            ),
            FullscreenPlaybackPanelPolicy.ACTION_ORDER,
        )

        val hd = FullscreenPlaybackPanelPolicy.metrics(1280, 720)
        assertEquals(140, hd.panelHeightPx)
        assertEquals(47, hd.progressRowHeightPx)
        assertEquals(93, hd.actionRowHeightPx)
        assertEquals(47, hd.focusCircleSizePx)
        assertEquals(33, hd.actionIconSizePx)
    }

    @Test
    fun progressAndTimeHandleUnknownOutOfRangeAndLongDurations() {
        assertEquals(250, FullscreenPlaybackPanelPolicy.progressPermille(2_500, 10_000))
        assertEquals(0, FullscreenPlaybackPanelPolicy.progressPermille(10_000, 0))
        assertEquals(0, FullscreenPlaybackPanelPolicy.progressPermille(-1, 10_000))
        assertEquals(1_000, FullscreenPlaybackPanelPolicy.progressPermille(20_000, 10_000))
        assertEquals("00:36", FullscreenPlaybackPanelPolicy.formatTime(36_000))
        assertEquals("01:05", FullscreenPlaybackPanelPolicy.formatTime(65_000))
        assertEquals("1:00:00", FullscreenPlaybackPanelPolicy.formatTime(3_600_000))
    }
}
