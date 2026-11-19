package com.homektv.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homektv.domain.SongFile;

import java.util.Locale;

/** Authoritative media choice shared by Android TV and H5 Web. */
public record PlaybackDescriptor(
        String kind,
        String status,
        Long sourceFileId,
        Long variantId,
        String streamUrl,
        int audioTracks,
        Integer vocalTrackIndex,
        AudioLayoutDto audioLayout,
        String errorCode,
        String errorMessage,
        @JsonInclude(JsonInclude.Include.NON_NULL) String format
) {
    public PlaybackDescriptor {
        audioLayout = audioLayout == null ? AudioLayoutDto.normalStereo() : audioLayout;
    }

    /** Source-compatible constructor for callers predating container format propagation. */
    public PlaybackDescriptor(
            String kind,
            String status,
            Long sourceFileId,
            Long variantId,
            String streamUrl,
            int audioTracks,
            Integer vocalTrackIndex,
            AudioLayoutDto audioLayout,
            String errorCode,
            String errorMessage) {
        this(kind, status, sourceFileId, variantId, streamUrl, audioTracks, vocalTrackIndex,
                audioLayout, errorCode, errorMessage, null);
    }

    public static PlaybackDescriptor idle() {
        return new PlaybackDescriptor("NONE", "IDLE", null, null, null, 0, null,
                AudioLayoutDto.normalStereo(), null, null, null);
    }

    public static PlaybackDescriptor nativeSource(SongFile source) {
        return new PlaybackDescriptor("NATIVE", "READY", source.getId(), null,
                "/api/stream/" + source.getId(), source.getAudioTracks(), source.getVocalTrackIndex(),
                AudioLayoutDto.from(source), null, null, resolvedFormat(source));
    }

    public static PlaybackDescriptor liveTranscode(SongFile source) {
        return new PlaybackDescriptor("LIVE_TRANSCODE", "READY", source.getId(), null,
                "/api/stream/" + source.getId() + "?transcode=true", source.getAudioTracks(),
                source.getVocalTrackIndex(), AudioLayoutDto.from(source), null, null, resolvedFormat(source));
    }

    public static PlaybackDescriptor failed(SongFile source, String errorCode, String errorMessage) {
        return new PlaybackDescriptor("TRANSCODE", "FAILED", source == null ? null : source.getId(), null,
                null, source == null ? 0 : source.getAudioTracks(),
                source == null ? null : source.getVocalTrackIndex(),
                source == null ? AudioLayoutDto.normalStereo() : AudioLayoutDto.from(source),
                errorCode, errorMessage, resolvedFormat(source));
    }

    private static String resolvedFormat(SongFile source) {
        if (source == null) return null;
        String format = source.getFormat();
        if (format != null) {
            String trimmed = format.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && !"unknown".equals(trimmed)) {
                return trimmed;
            }
        }
        String path = source.getFilePath();
        if (path == null || path.isBlank()) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot > slash && dot + 1 < path.length()) {
            return path.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
