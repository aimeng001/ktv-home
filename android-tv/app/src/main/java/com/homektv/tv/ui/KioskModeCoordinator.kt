package com.homektv.tv.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 抽象空闲定时器调度器，便于纯 JVM 测试与 Android Handler 解耦。
 */
interface IdleTimerScheduler {
    fun postDelayed(delayMs: Long, action: Runnable)
    fun cancel(action: Runnable)
}

/**
 * Android 平台默认的 Handler 定时调度器。
 */
class HandlerIdleTimerScheduler(
    private val handler: android.os.Handler = android.os.Handler(android.os.Looper.getMainLooper())
) : IdleTimerScheduler {
    override fun postDelayed(delayMs: Long, action: Runnable) {
        handler.postDelayed(action, delayMs)
    }

    override fun cancel(action: Runnable) {
        handler.removeCallbacks(action)
    }
}

/**
 * 电视全屏 MV 与画中画点歌台状态协调器。
 *
 * Coordinates between Fullscreen MV playback and Kiosk Ordering mode,
 * enforcing state-aware idle timeout without interrupting active user ordering.
 */
class KioskModeCoordinator(
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val scheduler: IdleTimerScheduler? = null,
) {
    private val _isKioskActive = MutableStateFlow(false)
    val isKioskActive: StateFlow<Boolean> = _isKioskActive

    private var isModalOpened = false
    private var isPlayingMedia = true

    private val idleAction = Runnable {
        if (!isModalOpened && _isKioskActive.value && isPlayingMedia) {
            toggleKiosk(false)
        }
    }

    fun setPlaybackActive(active: Boolean) {
        isPlayingMedia = active
        if (active) {
            resetIdleTimer()
        } else {
            scheduler?.cancel(idleAction)
        }
    }

    fun toggleKiosk(active: Boolean) {
        _isKioskActive.value = active
        if (active) {
            if (isPlayingMedia) resetIdleTimer() else scheduler?.cancel(idleAction)
        } else {
            scheduler?.cancel(idleAction)
        }
    }

    fun resetIdleTimer() {
        scheduler?.cancel(idleAction)
        if (_isKioskActive.value && !isModalOpened && isPlayingMedia) {
            scheduler?.postDelayed(idleTimeoutMs, idleAction)
        }
    }

    fun setModalActive(active: Boolean) {
        isModalOpened = active
        if (active) {
            scheduler?.cancel(idleAction)
        } else {
            resetIdleTimer()
        }
    }

    fun isIdleTimeoutSuspended(): Boolean = isModalOpened

    fun destroy() {
        _isKioskActive.value = false
        scheduler?.cancel(idleAction)
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 15_000L
    }
}
