package com.homektv.library;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts the legacy single artist credit into independent artist names.
 * Explicit collaboration separators cover filename and manually entered
 * credits without treating a hyphen inside a name as a field boundary.
 */
public final class ArtistCreditParser {
    private static final Pattern COLLABORATOR_SEPARATOR =
            Pattern.compile("\\s*(?:_|、|&|＆|/|／|,|，|;|；|(?<!\\+)\\+(?!\\+))\\s*");

    private ArtistCreditParser() {}

    public static List<String> parse(String artistCredit) {
        return parse(artistCredit, List.of());
    }

    /**
     * Splits explicit collaboration separators first. Whitespace is treated
     * as a collaboration separator only when the complete credit can be
     * covered by known artist names; otherwise the original credit is kept.
     */
    public static List<String> parse(String artistCredit, Collection<String> knownArtists) {
        if (artistCredit == null || artistCredit.isBlank()) return List.of();
        String trimmed = artistCredit.trim();
        String exact = exactKnownArtist(trimmed, knownArtists);
        if (exact != null) return List.of(exact);

        List<String> explicit = List.of(trimmed.split(COLLABORATOR_SEPARATOR.pattern(), -1));
        List<String> result = new ArrayList<>();
        for (String part : explicit) {
            List<String> split = splitByKnownArtists(part, knownArtists);
            if (split != null) {
                result.addAll(split);
                continue;
            }

            List<String> plusSplit = splitByKnownPlus(part, knownArtists);
            if (plusSplit != null) result.addAll(plusSplit);
            else result.add(part);
        }
        return normalize(result);
    }

    private static String exactKnownArtist(String artistCredit,
                                           Collection<String> knownArtists) {
        if (knownArtists == null) return null;
        String key = key(artistCredit);
        for (String artist : knownArtists) {
            if (artist != null && !artist.isBlank() && key(artist).equals(key)) {
                return artist.trim();
            }
        }
        return null;
    }

    /**
     * A plus sign is only a collaboration separator when every resulting
     * token is a known artist. This preserves names such as C++ and routes
     * ambiguous expressions to the existing review path.
     */
    private static List<String> splitByKnownPlus(String artistCredit,
                                                  Collection<String> knownArtists) {
        if (artistCredit == null || !artistCredit.contains("+")
                || knownArtists == null || knownArtists.isEmpty()) return null;
        List<String> parts = Arrays.stream(artistCredit.split("\\+", -1))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.size() < 2) return null;
        List<String> matched = new ArrayList<>(parts.size());
        for (String part : parts) {
            String exact = exactKnownArtist(part, knownArtists);
            if (exact == null) return null;
            matched.add(exact);
        }
        return matched;
    }

    private static List<String> splitByKnownArtists(String artistCredit,
                                                    Collection<String> knownArtists) {
        if (artistCredit == null || !artistCredit.trim().matches(".*\\s+.*")
                || knownArtists == null || knownArtists.isEmpty()) return null;

        Map<String, String> known = new LinkedHashMap<>();
        for (String artist : knownArtists) {
            if (artist == null || artist.isBlank()) continue;
            String cleaned = artist.trim();
            known.putIfAbsent(key(cleaned), cleaned);
        }
        if (known.isEmpty()) return null;

        String exact = known.get(key(artistCredit));
        if (exact != null) return List.of(exact);

        List<String> tokens = Arrays.stream(artistCredit.trim().split("\\s+"))
                .filter(token -> !token.isBlank()).toList();
        Map<Integer, List<String>> memo = new HashMap<>();
        List<String> matched = coverTokens(tokens, 0, known, memo);
        return matched == null || matched.size() < 2 ? null : matched;
    }

    private static List<String> coverTokens(List<String> tokens, int from,
                                            Map<String, String> known,
                                            Map<Integer, List<String>> memo) {
        if (from == tokens.size()) return List.of();
        if (memo.containsKey(from)) return memo.get(from);

        // Try the longest known name first so names containing spaces remain
        // one credit when the database provides that evidence.
        for (int end = tokens.size(); end > from; end--) {
            String candidate = String.join(" ", tokens.subList(from, end));
            String matchedName = known.get(key(candidate));
            if (matchedName == null) continue;
            List<String> tail = coverTokens(tokens, end, known, memo);
            if (tail == null) continue;
            List<String> result = new ArrayList<>();
            result.add(matchedName);
            result.addAll(tail);
            memo.put(from, List.copyOf(result));
            return memo.get(from);
        }

        memo.put(from, null);
        return null;
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
