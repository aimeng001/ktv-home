package com.homektv.ws;

import com.homektv.web.dto.QueueSnapshot;

import java.util.List;

/** One bounded wire frame of a large queue snapshot. */
public record QueueSnapshotChunk(
        String eventType,
        String syncId,
        int index,
        int total,
        boolean last,
        QueueSnapshotHeader header,
        List<QueueSnapshot.QueueEntry> entries
) {
    public QueueSnapshotChunk {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
