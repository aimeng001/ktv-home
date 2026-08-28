package com.homektv.library;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts the legacy single artist credit into independent artist names.
 * The underscore is the established filename collaboration separator; the
 * other separators cover common manually entered credits without treating a
 * hyphen inside a name as a field boundary.
 */
public final class ArtistCreditParser {
    private static final Pattern COLLABORATOR_SEPARATOR =
            Pattern.compile("\\s*(?:_|、|&|＆|\\+)\\s*");

    private ArtistCreditParser() {}

    public static List<String> parse(String artistCredit) {
        if (artistCredit == null || artistCredit.isBlank()) return List.of();
        return normalize(List.of(artistCredit.split(COLLABORATOR_SEPARATOR.pattern(), -1)));
    }

    public static List<String> normalize(Collection<String> artistNames) {
        if (artistNames == null || artistNames.isEmpty()) return List.of();
        Map<String, String> unique = new LinkedHashMap<>();
        for (String artist : artistNames) {
            if (artist == null) continue;
            String cleaned = artist.trim();
            if (!cleaned.isBlank()) unique.putIfAbsent(key(cleaned), cleaned);
        }
        return List.copyOf(unique.values());
    }

    public static String key(String artistName) {
        return artistName == null ? ""
                : artistName.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
