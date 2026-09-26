package com.homektv.web.dto;

import com.homektv.domain.AudioLayout;
import com.homektv.domain.PlaybackVariant;
import com.homektv.domain.SongFile;

/** Authoritative media choice shared by Android, Windows, and H5. */
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
        String errorMessage
) {
    public PlaybackDescriptor {
        audioLayout = audioLayout == null ? AudioLayoutDto.normalStereo() : audioLayout;
    }

    public static PlaybackDescriptor idle() {
        return new PlaybackDescriptor("NONE", "IDLE", null, null, null, 0, null,
                AudioLayoutDto.normalStereo(), null, null);
    }

    public static PlaybackDescriptor nativeSource(SongFile source) {
        return new PlaybackDescriptor("NATIVE", "READY", source.getId(), null,
                "/api/stream/" + source.getId(), source.getAudioTracks(), source.getVocalTrackIndex(),
                AudioLayoutDto.from(source), null, null);
    }

    public static PlaybackDescriptor liveTranscode(SongFile source) {
        return new PlaybackDescriptor("LIVE_TRANSCODE", "READY", source.getId(), null,
                "/api/stream/" + source.getId() + "?transcode=true", source.getAudioTracks(),
                source.getVocalTrackIndex(), AudioLayoutDto.from(source), null, null);
    }

    public static PlaybackDescriptor preparing(SongFile source, PlaybackVariant variant) {
        return new PlaybackDescriptor("TRANSCODE", "PREPARING", source.getId(), variant.getId(), null,
                variant.getAudioTracks(), variant.getAccompanimentTrackIndex(), layout(variant), null, null);
    }

    public static PlaybackDescriptor ready(SongFile source, PlaybackVariant variant) {
        return new PlaybackDescriptor("TRANSCODE", "READY", source.getId(), variant.getId(),
                "/api/playback/stream/" + variant.getId(), variant.getAudioTracks(),
                variant.getAccompanimentTrackIndex(), layout(variant), null, null);
    }

    public static PlaybackDescriptor failed(SongFile source, PlaybackVariant variant) {
        return new PlaybackDescriptor("TRANSCODE", "FAILED", source.getId(), variant.getId(), null,
                variant.getAudioTracks(), variant.getAccompanimentTrackIndex(), layout(variant),
                variant.getErrorCode(), variant.getErrorMessage());
    }

    public static PlaybackDescriptor failed(SongFile source, String errorCode, String errorMessage) {
        return new PlaybackDescriptor("TRANSCODE", "FAILED", source == null ? null : source.getId(), null,
                null, source == null ? 0 : source.getAudioTracks(),
                source == null ? null : source.getVocalTrackIndex(),
                source == null ? AudioLayoutDto.normalStereo() : AudioLayoutDto.from(source),
                errorCode, errorMessage);
    }

    private static AudioLayoutDto layout(PlaybackVariant variant) {
        return new AudioLayoutDto(AudioLayout.from(variant.getAudioLayoutValue()),
                variant.getOriginalTrackIndex(), variant.getAccompanimentTrackIndex(),
                variant.getOriginalChannel(), variant.getAccompanimentChannel());
    }
}
