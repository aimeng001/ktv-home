package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.web.dto.AudioLayoutDto;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LargeSnapshotProtocolTest {

    private static final int MAX_MESSAGE_BYTES = 1_048_576;

    @Test
    void current_full_snapshot_fixture_exceeds_the_websocket_budget() throws Exception {
        QueueSnapshot snapshot = oversizedSnapshot();

        byte[] wire = new ObjectMapper().writeValueAsBytes(
                WsEvent.of(WsEvent.SYNC_FULL, snapshot));

        assertThat(wire).hasSizeGreaterThan(MAX_MESSAGE_BYTES);
    }

    @Test
    void playback_broadcast_never_sends_an_over_budget_snapshot_frame() throws Exception {
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("h5-large-snapshot");
        when(session.getAttributes()).thenReturn(Map.of("client_type", "h5"));
        when(session.isOpen()).thenReturn(true);
        broadcaster.register(session);

        broadcaster.broadcastPlayback(WsEvent.of(WsEvent.SYNC_FULL, oversizedSnapshot()));

        var message = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(message.capture());
        assertThat(message.getAllValues())
                .allSatisfy(frame -> assertThat(frame.getPayload().getBytes(StandardCharsets.UTF_8).length)
                        .isLessThanOrEqualTo(MAX_MESSAGE_BYTES));
        assertThat(message.getAllValues()).allSatisfy(frame ->
                assertThat(frame.getPayload()).contains("\"type\":\"snapshot_chunk\""));
        int entries = message.getAllValues().stream()
                .mapToInt(frame -> {
                    try {
                        return new ObjectMapper().readTree(frame.getPayload())
                                .path("payload").path("entries").size();
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .sum();
        assertThat(entries).isEqualTo(1_000);
    }

    private static QueueSnapshot oversizedSnapshot() {
        String longText = "歌名和歌手字段用于构造合法但超预算的快照。".repeat(24);
        SongDto song = new SongDto(1L, longText, longText, "", "AUDIO", false,
                180_000, "none", null, 0, null);
        List<QueueSnapshot.QueueEntry> entries = new ArrayList<>();
        for (long id = 1; id <= 1_000; id++) {
            entries.add(new QueueSnapshot.QueueEntry(id, song, id, longText, "waiting"));
        }
        return new QueueSnapshot(null, entries, "idle", 60, false, "accompaniment",
                AudioLayoutDto.normalStereo(), false, 0, 0, 0);
    }
}
