package com.homektv.tv.ui

/**
 * 画中画小窗角标文案策略，防止待机或纯音频时呈现假“正在播放”。
 */
object PipLabelPolicy {
    fun resolve(state: String?, hasPlaying: Boolean): String {
        return when {
            !hasPlaying || state == "idle" -> "待机中"
            state == "paused" -> "已暂停"
            else -> "正在播放"
        }
    }
}
