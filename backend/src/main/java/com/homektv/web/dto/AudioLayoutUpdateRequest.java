package com.homektv.web.dto;

import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;

/**
 * Admin request for changing one file's platform-neutral audio semantics.
 * The media file itself is never opened or rewritten by this request.
 */
public record AudioLayoutUpdateRequest(
        AudioLayout layout,
        Integer originalTrackIndex,
        Integer accompanimentTrackIndex,
        AudioChannel originalChannel,
        AudioChannel accompanimentChannel
) {
    public AudioLayoutUpdateRequest {
        layout = layout == null ? AudioLayout.NORMAL_STEREO : layout;
        originalChannel = originalChannel == null ? AudioChannel.LEFT : originalChannel;
        accompanimentChannel = accompanimentChannel == null ? AudioChannel.RIGHT : accompanimentChannel;
    }
}
