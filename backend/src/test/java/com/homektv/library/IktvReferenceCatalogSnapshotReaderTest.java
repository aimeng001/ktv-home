package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IktvReferenceCatalogSnapshotReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IktvReferenceCatalogSnapshotReader reader = new IktvReferenceCatalogSnapshotReader(mapper);

    @Test
    void streamsAndValidatesReferenceRowsWithoutPaddingCatalogNumbers() throws Exception {
        byte[] snapshot = gzip("""
                {"reference_song_id":1,"catalog_number":"123456789","title":"歌,名","artist":"歌手甲","edition":"MV","language":"国语","category":"流行","type":"热歌","normalized_title":"歌,名","normalized_artist":"歌手甲","normalized_edition":"mv","special_tags":["抖音神曲"],"special_tag_conflict":false}
                """);
        IktvReferenceCatalogSnapshotReader.Manifest manifest = manifest(snapshot, 1);
        List<IktvSongReferenceMatcher.ReferenceSong> songs = new ArrayList<>();

        long count = reader.streamRows(new ByteArrayInputStream(snapshot), manifest, songs::add);

        assertThat(count).isEqualTo(1);
        assertThat(songs).singleElement().satisfies(song -> {
            assertThat(song.catalogNumber()).isEqualTo("123456789");
            assertThat(song.title()).isEqualTo("歌,名");
            assertThat(song.specialTags()).containsExactly("抖音神曲");
        });
    }

    @Test
    void rejectsSnapshotWhoseCompressedHashDoesNotMatchManifest() throws Exception {
        byte[] snapshot = gzip(validRow() + "\n");
        IktvReferenceCatalogSnapshotReader.Manifest manifest = new IktvReferenceCatalogSnapshotReader.Manifest(
                1, "a".repeat(64), "0".repeat(64), 1);

        assertThatThrownBy(() -> reader.streamRows(new ByteArrayInputStream(snapshot), manifest, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SNAPSHOT_HASH_MISMATCH");
    }

    @Test
    void rejectsRowsThatDoNotMatchManifestCount() throws Exception {
        byte[] snapshot = gzip(validRow() + "\n");
        IktvReferenceCatalogSnapshotReader.Manifest manifest = manifest(snapshot, 2);

        assertThatThrownBy(() -> reader.streamRows(new ByteArrayInputStream(snapshot), manifest, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SNAPSHOT_ROW_COUNT_MISMATCH");
    }

    @Test
    void rejectsPlaybackOrPathFieldsInReferenceRows() throws Exception {
        String row = validRow().replace("}", ",\"playback_url\":\"http://invalid\"}");
        byte[] snapshot = gzip(row + "\n");
        IktvReferenceCatalogSnapshotReader.Manifest manifest = manifest(snapshot, 1);

        assertThatThrownBy(() -> reader.streamRows(new ByteArrayInputStream(snapshot), manifest, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SNAPSHOT_FIELD_NOT_ALLOWED");
    }

    @Test
    void packagedReferenceSnapshotPassesHashSchemaAndCountValidation() throws Exception {
        var loader = getClass().getClassLoader();
        IktvReferenceCatalogSnapshotReader.Manifest manifest;
        try (var input = loader.getResourceAsStream("iktv/iktv-reference-catalog.jsonl.manifest.json")) {
            assertThat(input).isNotNull();
            manifest = reader.readManifest(input);
        }
        long count;
        try (var input = new ClassPathResource("iktv/iktv-reference-catalog.jsonl.gz").getInputStream()) {
            count = reader.streamRows(input, manifest, ignored -> {});
        }
        assertThat(count).isEqualTo(254_191);
    }

    private IktvReferenceCatalogSnapshotReader.Manifest manifest(byte[] bytes, long count) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new IktvReferenceCatalogSnapshotReader.Manifest(1, "a".repeat(64), hash, count);
    }

    private static String validRow() {
        return "{\"reference_song_id\":1,\"catalog_number\":\"123456789\",\"title\":\"歌,名\","
                + "\"artist\":\"歌手甲\",\"edition\":\"MV\",\"language\":\"国语\","
                + "\"category\":\"流行\",\"type\":\"热歌\",\"normalized_title\":\"歌,名\","
                + "\"normalized_artist\":\"歌手甲\",\"normalized_edition\":\"mv\","
                + "\"special_tags\":[\"抖音神曲\"],\"special_tag_conflict\":false}";
    }

    private static byte[] gzip(String lines) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(lines.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
