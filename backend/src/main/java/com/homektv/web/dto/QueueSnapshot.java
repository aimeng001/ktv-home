package com.homektv.web.dto;

import java.util.List;

/**
 * Queue + playback status snapshot.  The playback descriptor is optional for
 * old callers but present in new wire snapshots so every client sees the same
 * native/preparing/ready/failed media decision.
 */
public record QueueSnapshot(
        NowPlaying playing,
        List<QueueEntry> list,
        String state,
        int volume,
        boolean muted,
        String vocalMode,
        AudioLayoutDto audioLayout,
        boolean tvOnline,
        long connectedPhones,
        long positionMs,
        long seekSequence,
        long stateRevision,
        PlaybackDescriptor playback
) {
    public QueueSnapshot(NowPlaying playing, List<QueueEntry> list, String state, int volume,
                         boolean muted, String vocalMode, boolean tvOnline, long connectedPhones) {
        this(playing, list, state, volume, muted, vocalMode, AudioLayoutDto.normalStereo(),
                tvOnline, connectedPhones, 0, 0, 0, PlaybackDescriptor.idle());
    }

    public QueueSnapshot(NowPlaying playing, List<QueueEntry> list, String state, int volume,
                         boolean muted, String vocalMode, AudioLayoutDto audioLayout,
                         boolean tvOnline, long connectedPhones) {
        this(playing, list, state, volume, muted, vocalMode, audioLayout,
                tvOnline, connectedPhones, 0, 0, 0, PlaybackDescriptor.idle());
    }

    public QueueSnapshot(NowPlaying playing, List<QueueEntry> list, String state, int volume,
                         boolean muted, String vocalMode, AudioLayoutDto audioLayout,
                         boolean tvOnline, long connectedPhones, long positionMs,
                         long seekSequence) {
        this(playing, list, state, volume, muted, vocalMode, audioLayout,
                tvOnline, connectedPhones, positionMs, seekSequence, 0, PlaybackDescriptor.idle());
    }

    /** Source-compatible constructor retaining the previous full shape. */
    public QueueSnapshot(NowPlaying playing, List<QueueEntry> list, String state, int volume,
                         boolean muted, String vocalMode, AudioLayoutDto audioLayout,
                         boolean tvOnline, long connectedPhones, long positionMs,
                         long seekSequence, long stateRevision) {
        this(playing, list, state, volume, muted, vocalMode, audioLayout,
                tvOnline, connectedPhones, positionMs, seekSequence, stateRevision,
                PlaybackDescriptor.idle());
    }

    public QueueSnapshot {
        audioLayout = audioLayout == null ? AudioLayoutDto.normalStereo() : audioLayout;
        playback = playback == null ? PlaybackDescriptor.idle() : playback;
    }

    public record NowPlaying(Long queueId, SongDto song, String orderedByNick) {}

    public record QueueEntry(Long queueId, SongDto song, Long orderedBy, String orderedByNick, String status) {}
}
