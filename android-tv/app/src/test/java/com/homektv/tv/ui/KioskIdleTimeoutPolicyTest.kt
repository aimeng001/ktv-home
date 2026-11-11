package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskIdleTimeoutPolicyTest {

    private data class Inputs(
        val autoFullscreenEnabled: Boolean,
        val hasVisibleVideo: Boolean,
        val externalDisplayActive: Boolean,
        val isPlaying: Boolean,
        val hasActiveModal: Boolean,
    )

    @Test
    fun disabledAutoFullscreen_keepsOrderingDashboardInsteadOfExitingToBlankPage() {
        assertFalse(
            KioskIdleTimeoutPolicy.shouldEnterFullscreen(
                autoFullscreenEnabled = false,
                hasVisibleVideo = true,
                externalDisplayActive = false,
                isPlaying = true,
                hasActiveModal = false,
            ),
        )
    }

    @Test
    fun activeVideoEntersFullscreenOnlyWhenEnabledAndUnblocked() {
        assertTrue(
            KioskIdleTimeoutPolicy.shouldEnterFullscreen(
                autoFullscreenEnabled = true,
                hasVisibleVideo = true,
                externalDisplayActive = false,
                isPlaying = true,
                hasActiveModal = false,
            ),
        )
    }

    @Test
    fun fullscreenIsBlockedWhenVideoIsUnavailableOrAnotherModalIsActive() {
        val disabledCases = listOf(
            Inputs(autoFullscreenEnabled = true, hasVisibleVideo = false, externalDisplayActive = false, isPlaying = true, hasActiveModal = false),
            Inputs(autoFullscreenEnabled = true, hasVisibleVideo = true, externalDisplayActive = true, isPlaying = true, hasActiveModal = false),
            Inputs(autoFullscreenEnabled = true, hasVisibleVideo = true, externalDisplayActive = false, isPlaying = false, hasActiveModal = false),
            Inputs(autoFullscreenEnabled = true, hasVisibleVideo = true, externalDisplayActive = false, isPlaying = true, hasActiveModal = true),
        )

        disabledCases.forEach { values ->
            assertFalse(
                KioskIdleTimeoutPolicy.shouldEnterFullscreen(
                    autoFullscreenEnabled = values.autoFullscreenEnabled,
                    hasVisibleVideo = values.hasVisibleVideo,
                    externalDisplayActive = values.externalDisplayActive,
                    isPlaying = values.isPlaying,
                    hasActiveModal = values.hasActiveModal,
                ),
            )
        }
    }
}
