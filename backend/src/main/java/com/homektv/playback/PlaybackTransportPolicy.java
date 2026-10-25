package com.homektv.playback;

import com.homektv.domain.SongFile;

import java.util.Locale;
import java.util.Set;

/** Single server-side decision for native Range versus the legacy live pipe. */
public final class PlaybackTransportPolicy {
    private static final Set<String> LEGACY_FORMATS = Set.of("rm", "rmvb", "realmedia");

    private PlaybackTransportPolicy() {}

    public static boolean isVideo(SongFile source) {
        return source != null && ("MV".equalsIgnoreCase(source.getMediaType())
                || "KTV_VIDEO".equalsIgnoreCase(source.getMediaType()));
    }

    public static boolean requiresLiveTranscode(SongFile source, boolean forceTranscode) {
        if (source == null) return false;
        if (forceTranscode) return true;
        if (!isVideo(source)) return false;

        String format = source.getFormat() == null
                ? "" : source.getFormat().trim().toLowerCase(Locale.ROOT);
        String path = source.getFilePath() == null
                ? "" : source.getFilePath().toLowerCase(Locale.ROOT);
        return LEGACY_FORMATS.stream().anyMatch(
                legacy -> format.contains(legacy) || path.endsWith("." + legacy));
    }
}
