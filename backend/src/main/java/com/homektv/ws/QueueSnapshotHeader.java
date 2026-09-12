package com.homektv.ws;

import com.homektv.web.dto.AudioLayoutDto;
import com.homektv.web.dto.QueueSnapshot;

/** Snapshot fields repeated once per chunk instead of once per queue entry. */
public record QueueSnapshotHeader(
        QueueSnapshot.NowPlaying playing,
        String state,
        int volume,
        boolean muted,
        String vocalMode,
        AudioLayoutDto audioLayout,
        boolean tvOnline,
        long connectedPhones,
        long positionMs,
        long seekSequence
) {
    public QueueSnapshotHeader {
        audioLayout = audioLayout == null ? AudioLayoutDto.normalStereo() : audioLayout;
    }

    public static QueueSnapshotHeader from(QueueSnapshot snapshot) {
        return new QueueSnapshotHeader(snapshot.playing(), snapshot.state(), snapshot.volume(),
                snapshot.muted(), snapshot.vocalMode(), snapshot.audioLayout(),
                snapshot.tvOnline(), snapshot.connectedPhones(), snapshot.positionMs(),
                snapshot.seekSequence());
    }

    public QueueSnapshot toSnapshot(java.util.List<QueueSnapshot.QueueEntry> entries) {
        return new QueueSnapshot(playing, entries, state, volume, muted, vocalMode,
                audioLayout, tvOnline, connectedPhones, positionMs, seekSequence);
    }
}
