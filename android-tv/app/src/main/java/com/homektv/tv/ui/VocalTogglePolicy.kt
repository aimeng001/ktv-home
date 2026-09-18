package com.homektv.tv.ui

import com.homektv.tv.player.AudioPlaybackRoute

/**
 * 原伴唱切换策略，根据当前媒体的实际音频布局决定原伴唱开关使能与提示文案。
 *
 * Resolves whether the vocal toggle is operable based on the active audio layout,
 * avoiding invalid attempts to mute tracks on plain stereo streams.
 */
data class VocalTogglePolicy(
    val isEnabled: Boolean,
    val hint: String,
) {
    companion object {
        fun resolve(layout: String?, audioTracks: Int = 1): VocalTogglePolicy {
            val route = AudioPlaybackRoute.forLayout(layout, audioTracks)
            return when (route) {
                AudioPlaybackRoute.TRACK_SELECTION,
                AudioPlaybackRoute.PCM_CHANNEL_MAPPING -> VocalTogglePolicy(
                    isEnabled = true,
                    hint = "原唱/伴唱",
                )
                AudioPlaybackRoute.PASSTHROUGH -> VocalTogglePolicy(
                    isEnabled = false,
                    hint = "标准立体声(不可消音)",
                )
            }
        }

        fun resolveOsdText(vocalMode: String?): String {
            return if (vocalMode.equals("original", ignoreCase = true)) "当前：原唱" else "当前：伴唱"
        }

        fun toggleMode(currentMode: String?): String {
            return if (currentMode.equals("original", ignoreCase = true)) "accompaniment" else "original"
        }
    }
}
