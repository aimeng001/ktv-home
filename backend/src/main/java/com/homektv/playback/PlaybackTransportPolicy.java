package com.homektv.playback;

import com.homektv.domain.SongFile;

import java.util.Locale;
import java.util.Set;

/** Single server-side decision for native Range versus the legacy live pipe. */
public final class PlaybackTransportPolicy {
    private static final Set<String> LEGACY_FORMATS = Set.of("rm", "rmvb", "realmedia");
    /**
     * Video codecs that lack hardware decoders on common Android TV SoCs and browsers,
     * even when muxed inside modern containers such as MKV or AVI.
     */
    private static final Set<String> LEGACY_VIDEO_CODECS = Set.of(
            "rv10", "rv20", "rv30", "rv40",
            "wmv1", "wmv2", "wmv3",
            "msmpeg4", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3",
            "flv1", "vp6", "vp6a", "vp6f",
            "svq1", "svq3", "cinepak",
            "indeo3", "indeo4", "indeo5");

    private PlaybackTransportPolicy() {}

    public static boolean isVideo(SongFile source) {
        return source != null && ("MV".equalsIgnoreCase(source.getMediaType())
                || "KTV_VIDEO".equalsIgnoreCase(source.getMediaType()));
    }

    public static boolean isLegacyVideoCodec(String videoCodec) {
        if (videoCodec == null || videoCodec.isBlank()) return false;
        return LEGACY_VIDEO_CODECS.contains(videoCodec.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean requiresLiveTranscode(SongFile source, boolean forceTranscode) {
        if (source == null) return false;
        if (forceTranscode) return true;
        if (!isVideo(source)) return false;

        if (isLegacyVideoCodec(source.getVideoCodec())) {
            return true;
        }

        String format = source.getFormat() == null
                ? "" : source.getFormat().trim().toLowerCase(Locale.ROOT);
        String path = source.getFilePath() == null
                ? "" : source.getFilePath().toLowerCase(Locale.ROOT);
        return LEGACY_FORMATS.stream().anyMatch(
                legacy -> format.contains(legacy) || path.endsWith("." + legacy));
    }
}
