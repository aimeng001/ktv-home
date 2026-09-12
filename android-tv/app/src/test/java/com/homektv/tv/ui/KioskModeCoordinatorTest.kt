package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskModeCoordinatorTest {

    private class FakeIdleTimerScheduler : IdleTimerScheduler {
        var scheduledDelay: Long? = null
        var scheduledAction: Runnable? = null
        var isCancelled: Boolean = false

        override fun postDelayed(delayMs: Long, action: Runnable) {
            scheduledDelay = delayMs
            scheduledAction = action
            isCancelled = false
        }

        override fun cancel(action: Runnable) {
            if (scheduledAction == action) {
                isCancelled = true
            }
        }

        fun fire() {
            if (!isCancelled) {
                scheduledAction?.run()
            }
        }
    }

    @Test
    fun initialMode_isFullscreenMv() {
        val coordinator = KioskModeCoordinator(idleTimeoutMs = 20_000L)
        assertFalse(coordinator.isKioskActive.value)
    }

    @Test
    fun enterKiosk_activatesKioskAndPiP() {
        val coordinator = KioskModeCoordinator(idleTimeoutMs = 20_000L)
        coordinator.toggleKiosk(true)
        assertTrue(coordinator.isKioskActive.value)
    }

    @Test
    fun exitKiosk_deactivatesKiosk() {
        val coordinator = KioskModeCoordinator(idleTimeoutMs = 20_000L)
        coordinator.toggleKiosk(true)
        coordinator.toggleKiosk(false)
        assertFalse(coordinator.isKioskActive.value)
    }

    @Test
    fun modalDialogOpened_suspendsIdleTimeout() {
        val coordinator = KioskModeCoordinator(idleTimeoutMs = 20_000L)
        coordinator.toggleKiosk(true)
        coordinator.setModalActive(true)
        assertTrue(coordinator.isIdleTimeoutSuspended())
    }

    @Test
    fun modalDialogClosed_resumesIdleTimeout() {
        val coordinator = KioskModeCoordinator(idleTimeoutMs = 20_000L)
        coordinator.toggleKiosk(true)
        coordinator.setModalActive(true)
        coordinator.setModalActive(false)
        assertFalse(coordinator.isIdleTimeoutSuspended())
    }

    @Test
    fun idleTimeout_triggersKioskExit_afterDelay() {
        val scheduler = FakeIdleTimerScheduler()
        val coordinator = KioskModeCoordinator(idleTimeoutMs = 25_000L, scheduler = scheduler)

        coordinator.toggleKiosk(true)
        assertTrue(coordinator.isKioskActive.value)
        assertEquals(25_000L, scheduler.scheduledDelay)
        assertNotNull(scheduler.scheduledAction)
        assertFalse(scheduler.isCancelled)

        // Fire the scheduled timeout
        scheduler.fire()
        assertFalse("点歌台超时后应自动退回全屏 MV", coordinator.isKioskActive.value)
    }

    @Test
    fun modalActive_cancelsScheduledTimeout_andResumesOnDismiss() {
        val scheduler = FakeIdleTimerScheduler()
        val coordinator = KioskModeCoordinator(idleTimeoutMs = 25_000L, scheduler = scheduler)

        coordinator.toggleKiosk(true)
        assertFalse(scheduler.isCancelled)

        // 打开弹窗（如队列抽屉或输入法）
        coordinator.setModalActive(true)
        assertTrue(scheduler.isCancelled)

        // 弹窗关闭后重新调度
        coordinator.setModalActive(false)
        assertFalse(scheduler.isCancelled)
        assertEquals(25_000L, scheduler.scheduledDelay)
    }

    @Test
    fun destroy_cancelsScheduledTimer_andDeactivatesKiosk() {
        val scheduler = FakeIdleTimerScheduler()
        val coordinator = KioskModeCoordinator(idleTimeoutMs = 25_000L, scheduler = scheduler)

        coordinator.toggleKiosk(true)
        assertFalse(scheduler.isCancelled)

        coordinator.destroy()
        assertTrue(scheduler.isCancelled)
        assertFalse(coordinator.isKioskActive.value)
    }
}
