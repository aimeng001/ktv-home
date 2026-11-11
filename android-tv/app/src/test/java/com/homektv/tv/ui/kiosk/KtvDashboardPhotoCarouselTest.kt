package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvDashboardPhotoCarouselTest {
    @Test
    fun photoPairAdvancesEveryTenSecondsInTheReferenceOrder() {
        val scheduler = FakeScheduler()
        val updated = mutableListOf<Int>()
        val carousel = scheduler.createCarousel { index -> updated.add(index) }

        carousel.setAttached(true)
        carousel.setVisible(true)
        scheduler.advanceBy(KtvDashboardPhotoCarousel.INTERVAL_MS - 1)
        assertTrue(updated.isEmpty())
        scheduler.advanceBy(1)
        assertEquals(listOf(1), updated)
        scheduler.advanceBy(KtvDashboardPhotoCarousel.INTERVAL_MS)
        assertEquals(listOf(1, 2), updated)
        scheduler.advanceBy(KtvDashboardPhotoCarousel.INTERVAL_MS)
        assertEquals(listOf(1, 2, 0), updated)
    }

    @Test
    fun settingsAndQueuePausesFreezeThePairAndResumeWithOneFreshTimer() {
        val scheduler = FakeScheduler()
        val updated = mutableListOf<Int>()
        val carousel = scheduler.createCarousel { index -> updated.add(index) }
        carousel.setAttached(true)
        carousel.setVisible(true)
        scheduler.advanceBy(3_000)

        carousel.pause(KtvDashboardPhotoCarousel.PauseReason.SETTINGS_DIALOG)
        carousel.pause(KtvDashboardPhotoCarousel.PauseReason.QUEUE_DRAWER)
        scheduler.advanceBy(30_000)
        assertTrue(updated.isEmpty())
        assertEquals(0, scheduler.pendingCount)

        carousel.resume(KtvDashboardPhotoCarousel.PauseReason.SETTINGS_DIALOG)
        assertEquals(0, scheduler.pendingCount)
        carousel.resume(KtvDashboardPhotoCarousel.PauseReason.QUEUE_DRAWER)
        carousel.resume(KtvDashboardPhotoCarousel.PauseReason.QUEUE_DRAWER)
        assertEquals(1, scheduler.pendingCount)
        scheduler.advanceBy(KtvDashboardPhotoCarousel.INTERVAL_MS - 1)
        assertTrue(updated.isEmpty())
        scheduler.advanceBy(1)
        assertEquals(listOf(1), updated)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun hidingDetachingOrDisposingCancelsPendingRotation() {
        val scheduler = FakeScheduler()
        val updated = mutableListOf<Int>()
        val carousel = scheduler.createCarousel { index -> updated.add(index) }
        carousel.setAttached(true)
        carousel.setVisible(true)
        carousel.setVisible(false)
        scheduler.advanceBy(20_000)
        assertTrue(updated.isEmpty())
        assertEquals(0, scheduler.pendingCount)

        carousel.setVisible(true)
        carousel.setAttached(false)
        scheduler.advanceBy(20_000)
        assertTrue(updated.isEmpty())
        assertEquals(0, scheduler.pendingCount)

        carousel.setAttached(true)
        carousel.dispose()
        scheduler.advanceBy(20_000)
        assertTrue(updated.isEmpty())
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun dashboardViewAndModalEntrancesOwnTheCarouselLifecycle() {
        val dashboardView = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvCommercialDashboardView.kt").readText()
        assertTrue(dashboardView.contains("override fun onAttachedToWindow()"))
        assertTrue(dashboardView.contains("photoCarousel.setAttached(true)"))
        assertTrue(dashboardView.contains("override fun onDetachedFromWindow()"))
        assertTrue(dashboardView.contains("photoCarousel.setAttached(false)"))
        assertTrue(dashboardView.contains("photoCarousel.setVisible(isShown)"))

        val controller = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(controller.contains("PauseReason.QUEUE_DRAWER"))
        assertTrue(controller.contains("resumePhotoCarousel("))

        val activity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue(activity.contains("PauseReason.SETTINGS_DIALOG"))
        assertTrue(activity.contains("resumeDashboardPhotoCarousel("))
    }

    private class FakeScheduler {
        private var nowMs = 0L
        private val scheduled = linkedMapOf<Runnable, Long>()

        val pendingCount: Int get() = scheduled.size

        fun createCarousel(onIndexChanged: (Int) -> Unit) = KtvDashboardPhotoCarousel(
            postDelayed = { task, delay -> scheduled[task] = nowMs + delay },
            removeCallbacks = { task -> scheduled.remove(task) },
            onIndexChanged = onIndexChanged,
        )

        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val targetMs = nowMs + deltaMs
            while (true) {
                val next = scheduled.entries.minByOrNull { it.value } ?: break
                if (next.value > targetMs) break
                scheduled.remove(next.key)
                nowMs = next.value
                next.key.run()
            }
            nowMs = targetMs
        }
    }

    private fun locate(relativePath: String): java.io.File {
        val workingDirectory = java.io.File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(java.io.File::isFile) ?: error("Cannot locate $relativePath")
    }
}
