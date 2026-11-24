package com.homektv.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.AudioLayoutSource;
import com.homektv.domain.SongFile;

/**
 * Platform-neutral audio layout contract. It intentionally contains no
 * ExoPlayer, PCM, mpv, or other client implementation detail.
 *
 * <p>{@code source} is read-only provenance: it tells the admin UI whether the
 * current semantics were inherited from a legacy row, derived by the automatic
 * probe, or set by an administrator. It must never travel back through the
 * {@code PUT /admin/files/{id}/audio-layout} request body.</p>
 */
public record AudioLayoutDto(
        AudioLayout layout,
        Integer originalTrackIndex,
        Integer accompanimentTrackIndex,
        AudioChannel originalChannel,
        AudioChannel accompanimentChannel,
        // 可选字段：没有来源信息时不进 JSON，避免污染 android-tv 共享的协议快照。
        @JsonInclude(JsonInclude.Include.NON_NULL) AudioLayoutSource source
) {
    public AudioLayoutDto {
        layout = layout == null ? AudioLayout.NORMAL_STEREO : layout;
        originalChannel = originalChannel == null ? AudioChannel.LEFT : originalChannel;
        accompanimentChannel = accompanimentChannel == null ? AudioChannel.RIGHT : accompanimentChannel;
    }

    /** Source-compatible constructor for callers that predate provenance propagation. */
    public AudioLayoutDto(
            AudioLayout layout,
            Integer originalTrackIndex,
            Integer accompanimentTrackIndex,
            AudioChannel originalChannel,
            AudioChannel accompanimentChannel) {
        this(layout, originalTrackIndex, accompanimentTrackIndex, originalChannel, accompanimentChannel, null);
    }

    /** Synthetic default used when no file is known: provenance is unknown, so it stays null. */
    public static AudioLayoutDto normalStereo() {
        return new AudioLayoutDto(AudioLayout.NORMAL_STEREO, null, null, AudioChannel.LEFT, AudioChannel.RIGHT, null);
    }

    public static AudioLayoutDto from(SongFile file) {
        if (file == null) return normalStereo();
        return new AudioLayoutDto(
                file.getAudioLayout(),
                file.getOriginalTrackIndex(),
                file.getAccompanimentTrackIndex(),
                file.getOriginalChannel(),
                file.getAccompanimentChannel(),
                file.getAudioLayoutSource());
    }
}
