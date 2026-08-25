package com.homektv.web.dto;

import java.util.List;

/**
 * 队列 + 播放状态快照（详设§11.1 GET /queue，也用于 WS sync_full）。
 *
 * Queue and playback status snapshot (detailed design §11.1 GET /queue, also used for WS sync_full).
 */
public record QueueSnapshot(
        NowPlaying playing,
        List<QueueEntry> list,
        String state,      // idle/playing/paused
        int volume,
        boolean muted,
        String vocalMode,  // original/accompaniment
        AudioLayoutDto audioLayout,
        boolean tvOnline,  // TV 是否在线（P2.13：H5 据此显示「电视未连接」横幅） / Whether TV is online (P2.13: H5 shows "TV not connected" banner based on this)
        long connectedPhones
) {
    /** Source-compatible constructor retaining the original snapshot shape. */
    public QueueSnapshot(NowPlaying playing, List<QueueEntry> list, String state, int volume,
                         boolean muted, String vocalMode, boolean tvOnline, long connectedPhones) {
        this(playing, list, state, volume, muted, vocalMode, AudioLayoutDto.normalStereo(),
                tvOnline, connectedPhones);
    }

    public QueueSnapshot {
        audioLayout = audioLayout == null ? AudioLayoutDto.normalStereo() : audioLayout;
    }

    /** 正在播放 / Now playing */
    public record NowPlaying(Long queueId, SongDto song, String orderedByNick) {}

    /** 队列条目 / Queue entry */
    public record QueueEntry(Long queueId, SongDto song, Long orderedBy, String orderedByNick, String status) {}
}
