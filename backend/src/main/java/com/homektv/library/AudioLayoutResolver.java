package com.homektv.library;

import com.homektv.domain.AudioLayout;
import com.homektv.domain.AudioLayoutSource;

/**
 * Resolves a persisted semantic layout without allowing a global default to
 * overwrite an explicit or legacy per-file decision.
 */
public final class AudioLayoutResolver {

    public record Result(AudioLayout layout, AudioLayoutSource source) {}

    public Result resolve(
            LibraryMode mode,
            AudioLayoutSource source,
            AudioLayout stored,
            int audioTracks,
            AudioLayout configuredExternalDefault
    ) {
        AudioLayout current = stored == null ? AudioLayout.NORMAL_STEREO : stored;
        AudioLayoutSource effectiveSource = source == null
                ? AudioLayoutSource.LEGACY : source;

        if (effectiveSource == AudioLayoutSource.MANUAL
                || effectiveSource == AudioLayoutSource.LEGACY) {
            if (current == AudioLayout.DUAL_TRACK && audioTracks < 2) {
                return new Result(AudioLayout.NORMAL_STEREO, effectiveSource);
            }
            return new Result(current, effectiveSource);
        }

        if (mode == LibraryMode.EXTERNAL_READ_ONLY) {
            AudioLayout configured = configuredExternalDefault == null
                    ? AudioLayout.NORMAL_STEREO : configuredExternalDefault;
            if (configured == AudioLayout.DUAL_TRACK && audioTracks < 2) {
                return new Result(AudioLayout.NORMAL_STEREO, AudioLayoutSource.AUTO_DEFAULT);
            }
            if (configured == AudioLayout.DUAL_CHANNEL && audioTracks >= 2) {
                return new Result(AudioLayout.DUAL_TRACK, AudioLayoutSource.AUTO_DEFAULT);
            }
            return new Result(configured, AudioLayoutSource.AUTO_DEFAULT);
        }

        return new Result(
                audioTracks >= 2 ? AudioLayout.DUAL_TRACK : AudioLayout.NORMAL_STEREO,
                AudioLayoutSource.AUTO_DEFAULT);
    }
}
