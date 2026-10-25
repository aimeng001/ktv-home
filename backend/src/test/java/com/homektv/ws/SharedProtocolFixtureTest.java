package com.homektv.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homektv.web.ApiException;
import com.homektv.web.GlobalExceptionHandler;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.web.dto.SongDetailDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SharedProtocolFixtureTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serverChunkerEmitsTheSharedSnapshotContract() throws Exception {
        JsonNode expected = mapper.readTree(fixture("snapshot_chunk.json"));
        QueueSnapshotChunk sourceChunk = mapper.treeToValue(expected.path("payload"), QueueSnapshotChunk.class);
        QueueSnapshot snapshot = sourceChunk.header().toSnapshot(sourceChunk.entries());
        List<QueueSnapshotChunk> emitted = new ArrayList<>();

        QueueSnapshotChunker.stream(sourceChunk.eventType(), snapshot, mapper, emitted::add);

        assertThat(emitted).hasSize(1);
        QueueSnapshotChunk chunk = emitted.getFirst();
        assertThat(chunk.syncId()).matches(UUID_REGEX);
        assertThat(chunk.index()).isZero();
        assertThat(chunk.total()).isEqualTo(1);
        assertThat(chunk.last()).isTrue();
        assertThat(chunk.header().positionMs()).isEqualTo(123456);
        assertThat(chunk.header().stateRevision()).isEqualTo(86);
        assertThat(chunk.header().audioLayout().layout().name()).isEqualTo("DUAL_TRACK");

        JsonNode actual = mapper.readTree(mapper.writeValueAsBytes(WsEvent.of(WsEvent.SNAPSHOT_CHUNK, chunk)));
        ((ObjectNode) expected.path("payload")).put("syncId", chunk.syncId());
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void songDetailContractCarriesNotReadyAndReversedDualChannelSemantics() throws Exception {
        JsonNode expected = mapper.readTree(fixture("song_detail_not_ready.json"));
        SongDetailDto detail = mapper.treeToValue(expected, SongDetailDto.class);

        assertThat(detail.files()).hasSize(1);
        assertThat(detail.files().getFirst().ready()).isFalse();
        assertThat(detail.files().getFirst().audioLayout().layout().name()).isEqualTo("DUAL_CHANNEL");
        assertThat(detail.files().getFirst().audioLayout().originalChannel().name()).isEqualTo("RIGHT");
        assertThat(detail.files().getFirst().audioLayout().accompanimentChannel().name()).isEqualTo("LEFT");
        JsonNode actual = mapper.readTree(mapper.writeValueAsBytes(detail));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void notReadyBusinessErrorKeepsTheSharedHttpConflictAndErrorEnvelope() throws Exception {
        JsonNode expected = mapper.readTree(fixture("business_error_song_not_ready.json"));

        var response = new GlobalExceptionHandler().handleApi(
                new ApiException(expected.path("code").asText(), expected.path("message").asText()));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        JsonNode actual = mapper.readTree(mapper.writeValueAsBytes(response.getBody()));
        assertThat(actual).isEqualTo(expected);
    }

    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static String fixture(String name) throws Exception {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("test-fixtures/ktv-protocol/v1").resolve(name);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
            directory = directory.getParent();
        }
        throw new IllegalStateException("Shared protocol fixture not found: " + name);
    }
}
