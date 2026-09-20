package com.homektv.domain;

/** Lifecycle of a server-generated playback derivative. */
public enum PlaybackVariantStatus {
    PREPARING,
    READY,
    FAILED,
    STALE
}
