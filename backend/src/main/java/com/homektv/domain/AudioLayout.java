package com.homektv.domain;

import java.util.Locale;

/**
 * Platform-neutral description of how a media source carries vocal and
 * accompaniment audio.
 */
public enum AudioLayout {
    NORMAL_STEREO,
    DUAL_TRACK,
    DUAL_CHANNEL;

    /** Unknown/null persisted values must not make an old song unloadable. */
    public static AudioLayout from(String value) {
        if (value == null || value.isBlank()) return NORMAL_STEREO;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return NORMAL_STEREO;
        }
    }
}
