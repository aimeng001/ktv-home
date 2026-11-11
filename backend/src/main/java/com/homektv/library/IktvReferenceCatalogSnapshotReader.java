package com.homektv.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Set;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Streams and verifies the packaged, non-playable IKTV reference catalog. */
@Component
public final class IktvReferenceCatalogSnapshotReader {
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern CATALOG_NUMBER = Pattern.compile("[0-9]{8,10}");
    private static final Set<String> ROW_FIELDS = Set.of(
            "reference_song_id", "catalog_number", "title", "artist", "edition", "language",
            "category", "type", "normalized_title", "normalized_artist", "normalized_edition",
            "special_tags", "special_tag_conflict");

    private final ObjectMapper objectMapper;

    public IktvReferenceCatalogSnapshotReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Manifest readManifest(InputStream input) throws IOException {
        JsonNode root = objectMapper.readTree(input);
        Manifest manifest = new Manifest(
                requiredInt(root, "format_version"),
                requiredText(root, "snapshot_version"),
                requiredText(root, "snapshot_sha256"),
                requiredLong(root, "reference_song_count"));
        if (manifest.formatVersion() != 1
                || !SHA256.matcher(manifest.snapshotVersion()).matches()
                || !SHA256.matcher(manifest.snapshotSha256()).matches()
                || manifest.referenceSongCount() <= 0) {
            throw new IllegalStateException("SNAPSHOT_MANIFEST_INVALID");
        }
        return manifest;
    }

    public long streamRows(InputStream compressedInput, Manifest manifest,
                           Consumer<IktvSongReferenceMatcher.ReferenceSong> consumer) throws IOException {
        MessageDigest digest = sha256Digest();
        long rows = 0;
        long previousId = 0;
        try (DigestInputStream digestedInput = new DigestInputStream(compressedInput, digest);
             GZIPInputStream gzipInput = new GZIPInputStream(digestedInput);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzipInput,
                     java.nio.charset.StandardCharsets.UTF_8), 64 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) throw new IllegalStateException("SNAPSHOT_BLANK_ROW");
                IktvSongReferenceMatcher.ReferenceSong song = parseRow(line);
                if (song.referenceSongId() <= previousId) {
                    throw new IllegalStateException("SNAPSHOT_REFERENCE_IDS_NOT_STRICTLY_SORTED");
                }
                previousId = song.referenceSongId();
                consumer.accept(song);
                rows++;
            }
        }

        String actualHash = HexFormat.of().formatHex(digest.digest());
        if (!MessageDigest.isEqual(actualHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                manifest.snapshotSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("SNAPSHOT_HASH_MISMATCH");
        }
        if (rows != manifest.referenceSongCount()) {
            throw new IllegalStateException("SNAPSHOT_ROW_COUNT_MISMATCH");
        }
        return rows;
    }

    private IktvSongReferenceMatcher.ReferenceSong parseRow(String line) throws IOException {
        JsonNode row = objectMapper.readTree(line);
        var fieldNames = row.fieldNames();
        while (fieldNames.hasNext()) {
            if (!ROW_FIELDS.contains(fieldNames.next())) {
                throw new IllegalStateException("SNAPSHOT_FIELD_NOT_ALLOWED");
            }
        }
        long id = requiredLong(row, "reference_song_id");
        if (id <= 0) throw new IllegalStateException("SNAPSHOT_REFERENCE_ID_INVALID");
        String number = requiredText(row, "catalog_number");
        if (!number.isEmpty() && !CATALOG_NUMBER.matcher(number).matches()) {
            throw new IllegalStateException("SNAPSHOT_CATALOG_NUMBER_INVALID");
        }
        String title = requiredText(row, "title");
        String artist = requiredText(row, "artist");
        String edition = requiredText(row, "edition");
        if (!IktvSongReferenceMatcher.normalize(title).equals(requiredText(row, "normalized_title"))
                || !IktvSongReferenceMatcher.normalize(artist).equals(requiredText(row, "normalized_artist"))
                || !IktvSongReferenceMatcher.normalize(edition).equals(requiredText(row, "normalized_edition"))) {
            throw new IllegalStateException("SNAPSHOT_NORMALIZED_KEY_MISMATCH");
        }
        return new IktvSongReferenceMatcher.ReferenceSong(
                id, number, title, artist, edition,
                requiredText(row, "language"), requiredText(row, "category"), requiredText(row, "type"),
                readTextArray(row, "special_tags"), requiredBoolean(row, "special_tag_conflict"));
    }

    private static List<String> readTextArray(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || !value.isArray()) throw new IllegalStateException("SNAPSHOT_FIELD_INVALID_" + key);
        List<String> values = new ArrayList<>(value.size());
        for (JsonNode element : value) {
            if (!element.isTextual() || element.asText().isBlank()) {
                throw new IllegalStateException("SNAPSHOT_FIELD_INVALID_" + key);
            }
            values.add(element.asText());
        }
        return List.copyOf(values);
    }

    private static String requiredText(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || !value.isTextual()) throw new IllegalStateException("SNAPSHOT_FIELD_INVALID_" + key);
        return value.asText();
    }

    private static long requiredLong(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || !value.canConvertToLong()) throw new IllegalStateException("SNAPSHOT_FIELD_INVALID_" + key);
        return value.longValue();
    }

    private static int requiredInt(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || !value.canConvertToInt()) throw new IllegalStateException("SNAPSHOT_FIELD_INVALID_" + key);
        return value.intValue();
    }

    private static boolean requiredBoolean(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || !value.isBoolean()) throw new IllegalStateException("SNAPSHOT_FIELD_INVALID_" + key);
        return value.booleanValue();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", impossible);
        }
    }

    public record Manifest(int formatVersion, String snapshotVersion,
                           String snapshotSha256, long referenceSongCount) {}
}
