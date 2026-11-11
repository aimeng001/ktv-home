package com.homektv.tv.ui.kiosk

/** Lifecycle-aware, deterministic photo-pair rotation for the two central dashboard tiles. */
class KtvDashboardPhotoCarousel(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onIndexChanged: (Int) -> Unit,
    private val itemCount: Int = PHOTO_PAIR_COUNT,
    private val intervalMs: Long = INTERVAL_MS,
) {
    enum class PauseReason {
        SETTINGS_DIALOG,
        QUEUE_DRAWER,
    }

    init {
        require(itemCount > 0) { "Photo carousel requires at least one pair" }
        require(intervalMs > 0L) { "Photo carousel interval must be positive" }
    }

    private var attached = false
    private var visible = false
    private var scheduled = false
    private val pauseReasons = mutableSetOf<PauseReason>()
    private var currentIndex = 0

    private val tick = Runnable {
        scheduled = false
        if (!isEligible()) return@Runnable
        currentIndex = (currentIndex + 1) % itemCount
        onIndexChanged(currentIndex)
        updateSchedule()
    }

    fun setAttached(isAttached: Boolean) {
        attached = isAttached
        updateSchedule()
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        updateSchedule()
    }

    fun pause(reason: PauseReason) {
        if (pauseReasons.add(reason)) updateSchedule()
    }

    fun resume(reason: PauseReason) {
        if (pauseReasons.remove(reason)) updateSchedule()
    }

    fun dispose() {
        attached = false
        visible = false
        pauseReasons.clear()
        updateSchedule()
    }

    private fun isEligible(): Boolean = attached && visible && pauseReasons.isEmpty()

    private fun updateSchedule() {
        if (isEligible()) {
            if (!scheduled) {
                scheduled = true
                postDelayed(tick, intervalMs)
            }
        } else if (scheduled) {
            scheduled = false
            removeCallbacks(tick)
        }
    }

    companion object {
        const val PHOTO_PAIR_COUNT = 3
        const val INTERVAL_MS = 10_000L
    }
}
