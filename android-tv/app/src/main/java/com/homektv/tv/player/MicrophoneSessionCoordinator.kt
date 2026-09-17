package com.homektv.tv.player

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class MicrophoneSessionCoordinator {
    private val sessionGeneration = AtomicLong(0L)
    val running = AtomicBoolean(false)

    val isRunning: Boolean
        get() = running.get()

    fun beginSession(): Long {
        val session = sessionGeneration.incrementAndGet()
        running.set(true)
        return session
    }

    fun isCurrent(session: Long): Boolean =
        running.get() && sessionGeneration.get() == session

    fun endSession(session: Long) {
        if (sessionGeneration.get() == session) {
            running.set(false)
        }
    }

    fun stop() {
        sessionGeneration.incrementAndGet()
        running.set(false)
    }
}
