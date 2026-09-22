package com.homektv.library;

import com.homektv.web.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Stores exact, administrator-owned artist classification aliases. */
@Service
public class ArtistGenderDictionaryService {
    public static final int MAX_IMPORT_ENTRIES = 5_000;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_ALIASES_PER_ENTRY = 32;
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}]");
    private static final Set<String> ALLOWED_GENDERS = Set.of("男歌手", "女歌手", "组合", "未知");
    private static final String BUILTIN_RESOURCE = "artist-gender-dictionary.csv";

    private final JdbcTemplate jdbc;

    public ArtistGenderDictionaryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DictionaryEntry(String displayName, String gender, List<String> aliases) {}

    private record DictionaryRow(String aliasKey, String canonicalKey, String displayName, String gender) {}

    private record ExistingRow(String aliasKey, String canonicalKey) {}

    /** Imports administrator rows; existing admin rows with the same canonical key are updated. */
    @Transactional
    public int importEntries(Collection<DictionaryEntry> entries) {
        return importRows(normalizeEntries(entries), "ADMIN");
    }

    /** Idempotently seeds the small offline dictionary without overwriting admin rows. */
    @Transactional
    public int seedBuiltin() {
        List<DictionaryEntry> entries;
        try {
            entries = readBuiltinEntries();
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取内置歌手分类词典", failure);
        }
        return importRows(normalizeEntries(entries), "BUILTIN");
    }

    private int importRows(List<DictionaryRow> rows, String source) {
        if (rows.isEmpty()) return 0;
        validateExistingAliases(rows);
        String sql = "BUILTIN".equals(source) ? """
                INSERT INTO artist_gender_dictionary(
                    alias_key, canonical_key, display_name, gender, source, enabled, updated_at)
                VALUES (?, ?, ?, ?, 'BUILTIN', TRUE, now())
                ON CONFLICT (alias_key) DO NOTHING
                """ : """
                INSERT INTO artist_gender_dictionary(
                    alias_key, canonical_key, display_name, gender, source, enabled, updated_at)
                VALUES (?, ?, ?, ?, 'ADMIN', TRUE, now())
                ON CONFLICT (alias_key) DO UPDATE SET
                    canonical_key = EXCLUDED.canonical_key,
                    display_name = EXCLUDED.display_name,
                    gender = EXCLUDED.gender,
                    source = 'ADMIN',
                    enabled = TRUE,
                    updated_at = now()
                """;
        jdbc.batchUpdate(sql, rows, rows.size(), (statement, row) -> {
            statement.setString(1, row.aliasKey());
            statement.setString(2, row.canonicalKey());
            statement.setString(3, row.displayName());
            statement.setString(4, row.gender());
        });
        return rows.size();
    }

    private void validateExistingAliases(List<DictionaryRow> rows) {
        List<String> keys = rows.stream().map(DictionaryRow::aliasKey).distinct().toList();
        if (keys.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        List<ExistingRow> existing = jdbc.query(
                "SELECT alias_key, canonical_key FROM artist_gender_dictionary WHERE alias_key IN (" + placeholders + ")",
                (rs, index) -> new ExistingRow(rs.getString("alias_key"), rs.getString("canonical_key")),
                keys.toArray());
        Map<String, String> canonicalByAlias = new LinkedHashMap<>();
        existing.forEach(row -> canonicalByAlias.put(row.aliasKey(), row.canonicalKey()));
        for (DictionaryRow row : rows) {
            String previous = canonicalByAlias.get(row.aliasKey());
            if (previous != null && !previous.equals(row.canonicalKey())) {
                throw new ApiException("ARTIST_DICTIONARY_ALIAS_CONFLICT",
                        "歌手别名已绑定到其他歌手：" + row.displayName());
            }
        }
    }

    private List<DictionaryEntry> readBuiltinEntries() throws IOException {
        ClassPathResource resource = new ClassPathResource(BUILTIN_RESOURCE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            List<DictionaryEntry> entries = new ArrayList<>();
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                if (header) {
                    header = false;
                    if (line.strip().equals("display_name,gender,aliases")) continue;
                }
                String[] columns = line.split(",", 3);
                if (columns.length < 2) {
                    throw new IOException("内置歌手词典行格式错误");
                }
                List<String> aliases = columns.length == 3 && !columns[2].isBlank()
                        ? Arrays.stream(columns[2].split("\\|", -1)).toList()
                        : List.of();
                entries.add(new DictionaryEntry(columns[0], columns[1], aliases));
            }
            return entries;
        }
    }

    private List<DictionaryRow> normalizeEntries(Collection<DictionaryEntry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        if (entries.size() > MAX_IMPORT_ENTRIES) {
            throw new ApiException("ARTIST_DICTIONARY_TOO_LARGE",
                    "单次最多导入 " + MAX_IMPORT_ENTRIES + " 条歌手词典记录");
        }
        Map<String, DictionaryRow> rows = new LinkedHashMap<>();
        for (DictionaryEntry entry : entries) {
            if (entry == null) throw new ApiException("INVALID_ARTIST_DICTIONARY", "歌手词典记录不能为空");
            String displayName = clean(entry.displayName(), "歌手名称");
            String canonicalKey = ArtistCreditParser.key(displayName);
            String gender = cleanGender(entry.gender());
            List<String> aliases = entry.aliases() == null ? List.of() : entry.aliases();
            if (aliases.size() > MAX_ALIASES_PER_ENTRY) {
                throw new ApiException("ARTIST_DICTIONARY_TOO_LARGE", "单条歌手最多包含 "
                        + MAX_ALIASES_PER_ENTRY + " 个别名");
            }
            List<String> names = new ArrayList<>();
            names.add(displayName);
            names.addAll(aliases);
            for (String alias : names) {
                String cleanAlias = clean(alias, "歌手别名");
                String aliasKey = ArtistCreditParser.key(cleanAlias);
                DictionaryRow row = new DictionaryRow(aliasKey, canonicalKey, displayName, gender);
                DictionaryRow previous = rows.putIfAbsent(aliasKey, row);
                if (previous != null && (!previous.canonicalKey().equals(canonicalKey)
                        || !previous.gender().equals(gender))) {
                    throw new ApiException("ARTIST_DICTIONARY_ALIAS_CONFLICT",
                            "本次导入包含冲突的歌手别名：" + cleanAlias);
                }
            }
        }
        return List.copyOf(rows.values());
    }

    private String cleanGender(String gender) {
        String value = clean(gender, "歌手分类");
        if (!ALLOWED_GENDERS.contains(value)) {
            throw new ApiException("INVALID_ARTIST_GENDER", "歌手类型只能是男歌手、女歌手、组合或未知");
        }
        return value;
    }

    private String clean(String value, String label) {
        if (value == null) throw new ApiException("INVALID_ARTIST_DICTIONARY", label + "不能为空");
        String cleaned = value.trim();
        if (cleaned.isBlank()) throw new ApiException("INVALID_ARTIST_DICTIONARY", label + "不能为空");
        if (cleaned.length() > MAX_NAME_LENGTH) {
            throw new ApiException("ARTIST_DICTIONARY_VALUE_TOO_LONG", label + "不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        if (CONTROL_CHARACTERS.matcher(cleaned).find()) {
            throw new ApiException("INVALID_ARTIST_DICTIONARY", label + "不能包含控制字符");
        }
        return cleaned;
    }
}
