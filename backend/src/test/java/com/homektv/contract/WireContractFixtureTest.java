package com.homektv.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies backend DTOs match the shared cross-client wire golden fixtures.
 */
class WireContractFixtureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void queueSnapshotMatchesSharedGoldenFixture() throws Exception {
        Path fixture = resolveRepoFile("contracts/wire-fixtures/queue_snapshot_playing.json");
        QueueSnapshot snapshot = mapper.readValue(Files.readString(fixture), QueueSnapshot.class);

        assertThat(snapshot.state()).isEqualTo("playing");
        assertThat(snapshot.stateRevision()).isEqualTo(9001L);
        assertThat(snapshot.playing()).isNotNull();
        assertThat(snapshot.playing().song().title()).isEqualTo("晴天");
        assertThat(snapshot.playback().kind()).isEqualTo("NATIVE");
        assertThat(snapshot.playback().format()).isEqualTo("mkv");
        assertThat(snapshot.playback().videoCodec()).isEqualTo("h264");
        assertThat(snapshot.playback().audioCodec()).isEqualTo("aac");
    }

    @Test
    void playbackDescriptorVariantsMatchSharedGoldenFixture() throws Exception {
        Path fixture = resolveRepoFile("contracts/wire-fixtures/playback_descriptor_variants.json");
        List<PlaybackDescriptor> descriptors = mapper.readValue(
                Files.readString(fixture), new TypeReference<>() {});

        assertThat(descriptors).hasSize(2);
        assertThat(descriptors.get(0).kind()).isEqualTo("NONE");
        assertThat(descriptors.get(1).kind()).isEqualTo("LIVE_TRANSCODE");
        assertThat(descriptors.get(1).format()).isEqualTo("rmvb");
        assertThat(descriptors.get(1).videoCodec()).isEqualTo("rv40");
        assertThat(descriptors.get(1).audioCodec()).isEqualTo("cook");
    }

    private static Path resolveRepoFile(String relative) {
        Path direct = Path.of("..", relative).normalize();
        if (Files.isRegularFile(direct)) return direct;
        return Path.of(relative).toAbsolutePath().normalize();
    }
}
