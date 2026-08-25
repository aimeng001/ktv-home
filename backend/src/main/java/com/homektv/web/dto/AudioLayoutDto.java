package com.homektv.web.dto;

import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.SongFile;

/**
 * Platform-neutral audio layout contract. It intentionally contains no
 * ExoPlayer, PCM, mpv, or other client implementation detail.
 */
public record AudioLayoutDto(
        AudioLayout layout,
        Integer originalTrackIndex,
        Integer accompanimentTrackIndex,
        AudioChannel originalChannel,
        AudioChannel accompanimentChannel
) {
    public AudioLayoutDto {
        layout = layout == null ? AudioLayout.NORMAL_STEREO : layout;
        originalChannel = originalChannel == null ? AudioChannel.LEFT : originalChannel;
        accompanimentChannel = accompanimentChannel == null ? AudioChannel.RIGHT : accompanimentChannel;
    }

    public static AudioLayoutDto normalStereo() {
        return new AudioLayoutDto(AudioLayout.NORMAL_STEREO, null, null,
                AudioChannel.LEFT, AudioChannel.RIGHT);
    }

    public static AudioLayoutDto from(SongFile file) {
        if (file == null) return normalStereo();
        return new AudioLayoutDto(
                file.getAudioLayout(),
                file.getOriginalTrackIndex(),
                file.getAccompanimentTrackIndex(),
                file.getOriginalChannel(),
                file.getAccompanimentChannel());
    }
}
