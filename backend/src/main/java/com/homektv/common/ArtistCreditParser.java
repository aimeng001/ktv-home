package com.homektv.common;

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
    private static final Pattern COLLABORATOR_SEPARATOR = Pattern.compile(
            "\\s*(?:_|、|&|＆|/|／|,|，|;|；|×|✕|(?<!\\+)\\+(?!\\+)|(?<!\\uFF0B)\\uFF0B(?!\\uFF0B))\\s*"
                    + "|\\s+(?i:feat\\.?|ft\\.?|featuring|vs\\.?)\\s+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private ArtistCreditParser() {}

    public static boolean hasExplicitSeparator(String artistCredit) {
        if (artistCredit == null || artistCredit.isBlank()) return false;
        return COLLABORATOR_SEPARATOR.matcher(artistCredit.trim()).find();
    }

    public static List<String> parse(String artistCredit) {
        return parse(artistCredit, List.of());
    }

    /**
     * Splits explicit collaboration separators first so legacy combined credits
     * in knownArtists cannot block splitting into individual artists. Whitespace
     * is treated as a collaboration separator only when the complete credit can
     * be covered by known artist names; otherwise the original credit is kept.
     */
    public static List<String> parse(String artistCredit, Collection<String> knownArtists) {
        if (artistCredit == null || artistCredit.isBlank()) return List.of();
        String trimmed = artistCredit.trim();
        KnownArtistsLookup lookup = knownArtists instanceof PreindexedKnownArtists pre
                ? pre.getLookup()
                : new KnownArtistsLookup(knownArtists);

        List<String> explicit = List.of(COLLABORATOR_SEPARATOR.split(trimmed, -1));
        if (explicit.size() <= 1) {
            String exact = lookup.findExact(trimmed);
            if (exact != null) return List.of(exact);
        }

        List<String> result = new ArrayList<>();
        for (String part : explicit) {
            List<String> split = splitByKnownArtists(part, lookup);
            if (split != null) {
                result.addAll(split);
                continue;
            }

            List<String> plusSplit = splitByKnownPlus(part, lookup);
            if (plusSplit != null) result.addAll(plusSplit);
            else result.add(part);
        }
        return normalize(result);
    }

    public static final class KnownArtistsLookup {
        private final Map<String, String> map;
        private final List<String> atomic;

        public KnownArtistsLookup(Collection<String> artists) {
            if (artists == null || artists.isEmpty()) {
                this.map = Map.of();
                this.atomic = List.of();
            } else {
                List<String> list = new ArrayList<>(artists.size());
                Map<String, String> m = new LinkedHashMap<>();
                for (String artist : artists) {
                    if (artist == null || artist.isBlank()) continue;
                    if (!hasExplicitSeparator(artist)) {
                        String cleaned = artist.trim();
                        list.add(cleaned);
                        m.putIfAbsent(key(cleaned), cleaned);
                    }
                }
                this.atomic = list;
                this.map = m;
            }
        }

        public String findExact(String candidate) {
            return candidate == null ? null : map.get(key(candidate));
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public List<String> atomic() {
            return atomic;
        }

        public Map<String, String> map() {
            return map;
        }
    }

    public static final class PreindexedKnownArtists extends java.util.AbstractList<String> {
        private final List<String> source;
        private final KnownArtistsLookup lookup;

        public PreindexedKnownArtists(Collection<String> source) {
            this.source = source instanceof List<String> list ? list : new ArrayList<>(source);
            this.lookup = new KnownArtistsLookup(source);
        }

        public KnownArtistsLookup getLookup() {
            return lookup;
        }

        @Override
        public String get(int index) {
            return source.get(index);
        }

        @Override
        public int size() {
            return source.size();
        }
    }

    public static List<String> preindex(Collection<String> knownArtists) {
        if (knownArtists instanceof PreindexedKnownArtists pre) return pre;
        return new PreindexedKnownArtists(knownArtists);
    }

    private static Collection<String> atomicKnownArtists(Collection<String> knownArtists) {
        if (knownArtists == null || knownArtists.isEmpty()) return List.of();
        List<String> filtered = new ArrayList<>(knownArtists.size());
        for (String artist : knownArtists) {
            if (artist == null || artist.isBlank()) continue;
            if (!hasExplicitSeparator(artist)) {
                filtered.add(artist);
            }
        }
        return filtered;
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
                                                  KnownArtistsLookup lookup) {
        if (artistCredit == null || !artistCredit.contains("+") || lookup == null || lookup.isEmpty()) return null;
        List<String> parts = Arrays.stream(artistCredit.split("\\+", -1))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.size() < 2) return null;
        List<String> matched = new ArrayList<>(parts.size());
        for (String part : parts) {
            String exact = lookup.findExact(part);
            if (exact == null) return null;
            matched.add(exact);
        }
        return matched;
    }

    private static List<String> splitByKnownArtists(String artistCredit,
                                                    KnownArtistsLookup lookup) {
        if (artistCredit == null || lookup == null || lookup.isEmpty()) return null;
        String trimmed = artistCredit.trim();
        if (!containsWhitespace(trimmed)) return null;

        Map<String, String> known = lookup.map();
        String exact = lookup.findExact(artistCredit);
        if (exact != null) return List.of(exact);

        String[] rawTokens = WHITESPACE_PATTERN.split(trimmed);
        List<String> tokens = new ArrayList<>(rawTokens.length);
        for (String token : rawTokens) {
            if (!token.isBlank()) tokens.add(token);
        }
        Map<Integer, List<String>> memo = new HashMap<>();
        List<String> matched = coverTokens(tokens, 0, known, memo);
        return matched == null || matched.size() < 2 ? null : matched;
    }

    private static boolean containsWhitespace(String s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            if (Character.isWhitespace(s.charAt(i))) return true;
        }
        return false;
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
        if (artistName == null || artistName.isEmpty()) return "";
        int len = artistName.length();
        StringBuilder sb = new StringBuilder(len);
        boolean allAscii = true;
        for (int i = 0; i < len; i++) {
            char c = artistName.charAt(i);
            // Match regex \s semantics (\s covers ASCII whitespace + Java regex whitespace) and Unicode whitespace.
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B || Character.isWhitespace(c)) {
                continue;
            }
            if (c >= 128) {
                allAscii = false;
            }
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        String compact = sb.toString();
        return allAscii ? compact : compact.toLowerCase(Locale.ROOT);
    }
}
