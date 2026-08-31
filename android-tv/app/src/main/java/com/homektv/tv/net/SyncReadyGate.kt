package com.homektv.tv.net

/** Tracks the handshake state separately from the underlying TCP/WebSocket state. */
internal class SyncReadyGate {
    private var open = false
    private var ready = false

    @Synchronized
    fun onOpen() {
        open = true
        ready = false
    }

    @Synchronized
    fun markReady(): Boolean {
        if (!open || ready) return false
        ready = true
        return true
    }

    @Synchronized
    fun onDisconnect() {
        open = false
        ready = false
    }

    @Synchronized
    fun isReady(): Boolean = ready
}
