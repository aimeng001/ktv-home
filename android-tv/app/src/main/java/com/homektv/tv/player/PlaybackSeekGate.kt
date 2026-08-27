package com.homektv.tv.player

/** Prevents an older or duplicate server seek snapshot from moving the local player backward. */
internal class PlaybackSeekGate {
    private var lastAppliedSequence = Long.MIN_VALUE

    fun shouldApply(sequence: Long): Boolean = sequence > lastAppliedSequence

    fun markApplied(sequence: Long) {
        if (sequence > lastAppliedSequence) lastAppliedSequence = sequence
    }

    fun reset() {
        lastAppliedSequence = Long.MIN_VALUE
    }
}
