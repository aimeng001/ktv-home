package com.homektv.library;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Reads the bounded, bundled IKTV singer reference CSV without title or fuzzy matching. */
@Component
public final class IktvArtistRegionReferenceReader {
    private static final int MAX_BYTES = 1_000_000;
    private static final Map<String, Classification> CLASSIFICATIONS = Map.of(
            "内地男", new Classification("内地", "男歌手"),
            "内地女", new Classification("内地", "女歌手"),
            "内地组合", new Classification("内地", "组合"),
            "港台男", new Classification("港台", "男歌手"),
            "港台女", new Classification("港台", "女歌手"),
            "港台组合", new Classification("港台", "组合"),
            "国外男", new Classification("国外", "男歌手"),
            "国外女", new Classification("国外", "女歌手"),
            "国外组合", new Classification("国外", "组合"));

    public Snapshot read(InputStream input, String expectedSha256, long expectedRows) throws IOException {
        byte[] bytes = readBounded(input);
        String sha256 = sha256(bytes);
        if (!sha256.equalsIgnoreCase(expectedSha256)) {
            byte[] crlfBytes = normalizeToCrlf(bytes);
            String crlfSha256 = sha256(crlfBytes);
            if (crlfSha256.equalsIgnoreCase(expectedSha256)) {
                bytes = crlfBytes;
                sha256 = crlfSha256;
            } else {
                throw new IllegalStateException("ARTIST_REFERENCE_SHA256_MISMATCH");
            }
        }
        List<List<String>> rows = parseCsv(new String(bytes, StandardCharsets.UTF_8));
        if (rows.isEmpty() || rows.get(0).size() != 2
                || !"name".equals(stripBom(rows.get(0).get(0)).trim())
                || !"category".equals(rows.get(0).get(1).trim())) {
            throw new IllegalStateException("ARTIST_REFERENCE_HEADER_INVALID");
        }

        Map<String, List<Entry>> byKey = new LinkedHashMap<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() != 2) throw new IllegalStateException("ARTIST_REFERENCE_ROW_INVALID");
            String name = row.get(0).trim();
            String sourceCategory = row.get(1).trim();
            String key = ArtistCreditParser.key(name);
            Classification classification = CLASSIFICATIONS.get(sourceCategory);
            if (name.isBlank() || key.isBlank() || classification == null) {
                throw new IllegalStateException("ARTIST_REFERENCE_VALUE_INVALID");
            }
            byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Entry(
                    key, name, classification.region(), classification.gender(), sourceCategory));
        }
        long sourceRows = rows.size() - 1L;
        if (sourceRows != expectedRows) throw new IllegalStateException("ARTIST_REFERENCE_ROW_COUNT_MISMATCH");

        List<Entry> entries = new ArrayList<>();
        int conflictingKeys = 0;
        for (Map.Entry<String, List<Entry>> grouped : byKey.entrySet()) {
            Set<String> categories = grouped.getValue().stream().map(Entry::sourceCategory).collect(
                    java.util.stream.Collectors.toSet());
            if (categories.size() > 1) {
                conflictingKeys++;
                continue;
            }
            entries.add(grouped.getValue().get(0));
        }
        entries.sort(Comparator.comparing(Entry::artistKey));
        return new Snapshot(sha256, sourceRows, List.copyOf(entries), conflictingKeys);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) throw new IllegalStateException("ARTIST_REFERENCE_TOO_LARGE");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>(2);
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quoted) {
                if (value == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(value);
                }
            } else if (value == '"' && field.isEmpty()) {
                quoted = true;
            } else if (value == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (value == '\n' || value == '\r') {
                row.add(field.toString());
                field.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(List.copyOf(row));
                row.clear();
                if (value == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
            } else {
                field.append(value);
            }
        }
        if (quoted) throw new IllegalStateException("ARTIST_REFERENCE_CSV_QUOTE_INVALID");
        if (!row.isEmpty() || !field.isEmpty()) {
            row.add(field.toString());
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static byte[] normalizeToCrlf(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String normalized = text.replace("\r\n", "\n").replace("\n", "\r\n");
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", impossible);
        }
    }

    private record Classification(String region, String gender) {}

    public record Entry(String artistKey, String displayName, String region,
                        String gender, String sourceCategory) {}

    public record Snapshot(String sha256, long sourceRowCount, List<Entry> entries,
                           int conflictingKeyCount) {}
}
