package com.homektv.domain;

import java.util.Locale;

/** Semantic channel names shared by server and playback clients. */
public enum AudioChannel {
    LEFT,
    RIGHT;

    public AudioChannel opposite() {
        return this == LEFT ? RIGHT : LEFT;
    }

    public static AudioChannel from(String value) {
        return from(value, LEFT);
    }

    public static AudioChannel from(String value, AudioChannel fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
