package com.homektv.common;

/**
 * Published when persisted playback state transitions between active playback and idle/paused
 * so background admission monitors can refresh immediately after transaction commit.
 */
public record PlaybackStateChangedEvent() {
}
