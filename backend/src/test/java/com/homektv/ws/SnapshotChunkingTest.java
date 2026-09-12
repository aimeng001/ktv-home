package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.web.dto.AudioLayoutDto;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotChunkingTest {

    @Test
    void chunks_preserve_order_and_each_wire_frame_stays_within_budget() {
        QueueSnapshot snapshot = snapshotWithEntries(1_000, "较长但合法的歌曲标题".repeat(12));
        List<QueueSnapshotChunk> chunks = new ArrayList<>();

        QueueSnapshotChunker.stream(WsEvent.SYNC_FULL, snapshot, new ObjectMapper(), chunks::add);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.total()).isEqualTo(chunks.size());
            assertThat(chunk.last()).isEqualTo(chunk.index() == chunks.size() - 1);
            assertThat(QueueSnapshotChunker.wireBytes(new ObjectMapper(), chunk))
                    .isLessThanOrEqualTo(WsProtocolLimits.MAX_MESSAGE_BYTES);
        });
        assertThat(chunks.stream().flatMap(chunk -> chunk.entries().stream())
                .map(QueueSnapshot.QueueEntry::queueId).toList())
                .containsExactlyElementsOf(snapshot.list().stream()
                        .map(QueueSnapshot.QueueEntry::queueId).toList());
    }

    @Test
    void a_single_entry_that_cannot_fit_is_rejected_instead_of_being_split() {
        QueueSnapshot snapshot = snapshotWithEntries(1, "超大字段".repeat(100_000));

        assertThatThrownBy(() -> QueueSnapshotChunker.stream(
                WsEvent.SYNC_FULL, snapshot, new ObjectMapper(), ignored -> { }))
                .isInstanceOf(QueueChunkTooLargeException.class);
    }

    private static QueueSnapshot snapshotWithEntries(int count, String title) {
        SongDto song = new SongDto(1L, title, title, "", "AUDIO", false,
                180_000, "none", null, 0, null);
        List<QueueSnapshot.QueueEntry> entries = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            entries.add(new QueueSnapshot.QueueEntry(id, song, id, "点歌人", "waiting"));
        }
        return new QueueSnapshot(null, entries, "idle", 60, false, "accompaniment",
                AudioLayoutDto.normalStereo(), false, 0, 0, 0);
    }
}
