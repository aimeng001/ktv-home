package com.homektv.library;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Resolves one Home song against the read-only IKTV reference catalog. */
@Component
public final class IktvSongReferenceMatcher {
    private static final Pattern VALID_CATALOG_NUMBER = Pattern.compile("[0-9]{8,10}");
    private static final Pattern NUMBER_DIRECTORY = Pattern.compile("[0-9]{8,10}");
    private static final Pattern SONG_CONTAINER_DIRECTORY = Pattern.compile("(?i)song[0-9]*");

    public Result match(HomeSong home, List<ReferenceSong> references) {
        if (home == null) return result(Status.IDENTITY_UNAVAILABLE, "HOME_SONG_MISSING", Method.NONE, null, 0);
        if (normalize(home.title()).isEmpty() || normalize(home.artist()).isEmpty()) {
            return result(Status.IDENTITY_UNAVAILABLE, "HOME_IDENTITY_INCOMPLETE", Method.NONE, null, 0);
        }

        Set<String> pathNumbers = pathCatalogNumbers(home);
        if (pathNumbers.size() > 1) {
            return result(Status.NUMBER_CONFLICT, "PATH_HAS_MULTIPLE_CATALOG_NUMBERS", Method.NONE, null,
                    pathNumbers.size());
        }

        String storedNumber = normalizeCatalogNumber(home.catalogNumber());
        if (!home.catalogNumber().isBlank() && storedNumber.isBlank()) {
            return result(Status.INVALID_CATALOG_NUMBER, "HOME_CATALOG_NUMBER_INVALID", Method.NONE, null, 0);
        }
        String pathNumber = pathNumbers.stream().findFirst().orElse("");
        if (!storedNumber.isBlank() && !pathNumber.isBlank() && !storedNumber.equals(pathNumber)) {
            return result(Status.NUMBER_CONFLICT, "HOME_AND_PATH_CATALOG_NUMBERS_DIFFER", Method.NONE, null, 0);
        }

        String number = storedNumber.isBlank() ? pathNumber : storedNumber;
        List<ReferenceSong> candidates = references == null ? List.of() : references.stream()
                .filter(reference -> reference != null)
                .toList();
        if (!number.isBlank()) {
            List<ReferenceSong> numbered = candidates.stream()
                    .filter(reference -> number.equals(reference.catalogNumber()))
                    .toList();
            if (numbered.isEmpty()) {
                return result(Status.NO_REFERENCE_CANDIDATE, "CATALOG_NUMBER_NOT_IN_REFERENCE", Method.CATALOG_NUMBER,
                        null, 0);
            }
            List<ReferenceSong> identityMatches = numbered.stream()
                    .filter(reference -> sameIdentity(home, reference))
                    .toList();
            if (identityMatches.isEmpty()) {
                return result(Status.NUMBER_IDENTITY_MISMATCH, "CATALOG_NUMBER_TITLE_OR_ARTIST_MISMATCH",
                        Method.CATALOG_NUMBER, null, numbered.size());
            }
            return selectUnique(home, identityMatches, Method.CATALOG_NUMBER);
        }

        List<ReferenceSong> identityMatches = candidates.stream()
                .filter(reference -> sameIdentity(home, reference))
                .toList();
        if (identityMatches.isEmpty()) {
            return result(Status.NO_REFERENCE_CANDIDATE, "TITLE_AND_ARTIST_NOT_IN_REFERENCE",
                    Method.TITLE_AND_ARTIST, null, 0);
        }
        return selectUnique(home, identityMatches, Method.TITLE_AND_ARTIST);
    }

    private static Result selectUnique(HomeSong home, List<ReferenceSong> candidates, Method method) {
        List<ReferenceSong> ordered = candidates.stream()
                .sorted(Comparator.comparingLong(ReferenceSong::referenceSongId))
                .toList();
        if (ordered.isEmpty()) {
            return result(Status.NO_REFERENCE_CANDIDATE, "NO_CANDIDATES", method, null, 0);
        }
        ReferenceSong first = ordered.get(0);
        boolean equivalent = ordered.stream().allMatch(candidate -> sameVersion(first, candidate));
        if (equivalent) {
            return result(Status.MATCHED, "IDENTITY_VERIFIED", method, first, ordered.size());
        }

        List<ReferenceSong> disambiguated = disambiguateVersions(home, ordered);
        if (!disambiguated.isEmpty()) {
            ReferenceSong disambiguatedFirst = disambiguated.get(0);
            boolean disambiguatedEquiv = disambiguated.stream().allMatch(candidate -> sameVersion(disambiguatedFirst, candidate));
            if (disambiguatedEquiv) {
                return result(Status.MATCHED, "IDENTITY_VERIFIED", method, disambiguatedFirst, disambiguated.size());
            }
        }

        return result(Status.AMBIGUOUS_VERSION, "REFERENCE_HAS_MULTIPLE_VERSIONS", method, null,
                ordered.size());
    }

    private static List<ReferenceSong> disambiguateVersions(HomeSong home, List<ReferenceSong> candidates) {
        if (home == null || candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        String fileLanguage = "";
        String fileCategory = "";
        boolean isLiveFile = false;
        boolean hasFileClues = false;

        List<String> rawPaths = new ArrayList<>();
        if (!home.relativePath().isBlank()) rawPaths.add(home.relativePath());
        if (!home.filePath().isBlank()) rawPaths.add(home.filePath());
        for (SourcePath source : home.sourcePaths()) {
            if (!source.relativePath().isBlank()) rawPaths.add(source.relativePath());
            if (!source.filePath().isBlank()) rawPaths.add(source.filePath());
        }

        for (String rawPath : rawPaths) {
            String fileName = extractFileName(rawPath);
            if (fileName.isBlank()) continue;
            hasFileClues = true;
            ParsedMeta meta = FilenameParser.parse(fileName);
            if (fileLanguage.isBlank() && !meta.language().isBlank()) {
                fileLanguage = meta.language();
            }
            if (fileCategory.isBlank() && !meta.category().isBlank()) {
                fileCategory = meta.category();
            }
            if (isLiveIdentifier(fileName)) {
                isLiveFile = true;
            }
        }

        if (!hasFileClues && fileLanguage.isBlank() && fileCategory.isBlank()) {
            return candidates;
        }

        List<ReferenceSong> filtered = candidates;

        if (!fileLanguage.isBlank()) {
            String normLang = normalize(fileLanguage);
            List<ReferenceSong> byLang = filtered.stream()
                    .filter(c -> !c.language().isBlank() && (normalize(c.language()).contains(normLang) || normLang.contains(normalize(c.language()))))
                    .toList();
            if (!byLang.isEmpty() && byLang.size() < filtered.size()) {
                filtered = byLang;
            }
        }

        if (!fileCategory.isBlank() && filtered.size() > 1) {
            String normCat = normalize(fileCategory);
            List<ReferenceSong> byCat = filtered.stream()
                    .filter(c -> !c.category().isBlank() && (normalize(c.category()).contains(normCat) || normCat.contains(normalize(c.category())) || normalize(c.type()).contains(normCat)))
                    .toList();
            if (!byCat.isEmpty() && byCat.size() < filtered.size()) {
                filtered = byCat;
            }
        }

        if (filtered.size() > 1) {
            if (!isLiveFile) {
                List<ReferenceSong> nonLive = filtered.stream()
                        .filter(c -> !isLiveReference(c))
                        .toList();
                if (!nonLive.isEmpty() && nonLive.size() < filtered.size()) {
                    filtered = nonLive;
                }
            } else {
                List<ReferenceSong> live = filtered.stream()
                        .filter(IktvSongReferenceMatcher::isLiveReference)
                        .toList();
                if (!live.isEmpty() && live.size() < filtered.size()) {
                    filtered = live;
                }
            }
        }

        return filtered;
    }

    private static boolean isLiveIdentifier(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("live") || lower.contains("现场") || lower.contains("演唱会");
    }

    private static boolean isLiveReference(ReferenceSong c) {
        return isLiveIdentifier(c.edition()) || isLiveIdentifier(c.category()) || isLiveIdentifier(c.type())
                || c.specialTags().stream().anyMatch(IktvSongReferenceMatcher::isLiveIdentifier);
    }

    private static String extractFileName(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "";
        String path = rawPath.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static final Pattern ARTIST_SPLIT_PATTERN =
            Pattern.compile("\\s*(?:[_+、&＆/／,，;；]|\\s+)\\s*");

    static boolean sameArtistNormalized(String homeArtist, String refArtist) {
        String normHome = normalize(homeArtist);
        String normRef = normalize(refArtist);
        if (normHome.equals(normRef)) {
            return true;
        }
        if (normHome.isEmpty() || normRef.isEmpty()) {
            return false;
        }
        Set<String> homeParts = splitArtists(homeArtist);
        Set<String> refParts = splitArtists(refArtist);
        return !homeParts.isEmpty() && homeParts.equals(refParts);
    }

    private static Set<String> splitArtists(String artist) {
        if (artist == null || artist.isBlank()) return Set.of();
        String[] tokens = ARTIST_SPLIT_PATTERN.split(artist);
        Set<String> result = new LinkedHashSet<>();
        for (String token : tokens) {
            String norm = normalize(token);
            if (!norm.isEmpty()) {
                result.add(norm);
            }
        }
        return result;
    }

    private static boolean sameIdentity(HomeSong home, ReferenceSong reference) {
        return !normalize(reference.title()).isEmpty()
                && !normalize(home.title()).isEmpty()
                && normalize(home.title()).equals(normalize(reference.title()))
                && sameArtistNormalized(home.artist(), reference.artist());
    }

    private static boolean sameVersion(ReferenceSong left, ReferenceSong right) {
        return normalize(left.catalogNumber()).equals(normalize(right.catalogNumber()))
                && normalize(left.edition()).equals(normalize(right.edition()))
                && normalize(left.language()).equals(normalize(right.language()))
                && normalize(left.category()).equals(normalize(right.category()))
                && normalize(left.type()).equals(normalize(right.type()))
                && left.specialTags().equals(right.specialTags())
                && left.specialTagConflict() == right.specialTagConflict();
    }

    public static Set<String> catalogNumberClues(HomeSong home) {
        if (home == null) return Set.of();
        Set<String> numbers = pathCatalogNumbers(home);
        String storedNumber = normalizeCatalogNumber(home.catalogNumber());
        if (!storedNumber.isBlank()) numbers.add(storedNumber);
        return Set.copyOf(numbers);
    }

    private static Set<String> pathCatalogNumbers(HomeSong home) {
        Set<String> numbers = new LinkedHashSet<>();
        addFilenameNumbers(home.relativePath(), numbers);
        addFilenameNumbers(home.filePath(), numbers);
        addDirectoryNumbers(home.relativePath(), numbers);
        addDirectoryNumbers(home.filePath(), numbers);
        for (SourcePath source : home.sourcePaths()) {
            addFilenameNumbers(source.relativePath(), numbers);
            addFilenameNumbers(source.filePath(), numbers);
            addDirectoryNumbers(source.relativePath(), numbers);
            addDirectoryNumbers(source.filePath(), numbers);
        }
        return numbers;
    }

    private static void addFilenameNumbers(String rawPath, Set<String> numbers) {
        if (rawPath == null || rawPath.isBlank()) return;
        String path = rawPath.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        MatcherResult parsed = catalogNumberFromFilename(fileName);
        if (!parsed.number().isBlank()) numbers.add(parsed.number());
    }

    private static MatcherResult catalogNumberFromFilename(String fileName) {
        ParsedMeta parsed = FilenameParser.parse(fileName);
        if (!parsed.catalogNumber().isBlank()) return new MatcherResult(parsed.catalogNumber());
        String stem = FilenameParser.stripExtension(fileName);
        return NUMBER_DIRECTORY.matcher(stem).matches() ? new MatcherResult(stem) : new MatcherResult("");
    }

    private static void addDirectoryNumbers(String rawPath, Set<String> numbers) {
        if (rawPath == null || rawPath.isBlank()) return;
        String[] segments = rawPath.replace('\\', '/').split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            String segment = segments[index].trim();
            if (NUMBER_DIRECTORY.matcher(segment).matches() && index > 0
                    && SONG_CONTAINER_DIRECTORY.matcher(segments[index - 1].trim()).matches()) {
                numbers.add(segment);
            }
        }
    }

    private static String normalizeCatalogNumber(String catalogNumber) {
        if (catalogNumber == null) return "";
        String value = catalogNumber.trim();
        return VALID_CATALOG_NUMBER.matcher(value).matches() ? value : "";
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static Result result(Status status, String reason, Method method,
                                 ReferenceSong candidate, int candidateCount) {
        String suggestedNumber = candidate == null ? "" : candidate.catalogNumber();
        return new Result(status, reason, method, candidate, suggestedNumber, candidateCount);
    }

    private record MatcherResult(String number) {}

    public enum Status {
        MATCHED,
        NO_REFERENCE_CANDIDATE,
        AMBIGUOUS_VERSION,
        NUMBER_IDENTITY_MISMATCH,
        NUMBER_CONFLICT,
        INVALID_CATALOG_NUMBER,
        IDENTITY_UNAVAILABLE
    }

    public enum Method {
        NONE,
        CATALOG_NUMBER,
        TITLE_AND_ARTIST
    }

    public record HomeSong(String catalogNumber, String title, String artist,
                           String relativePath, String filePath, Set<String> metadataLocks,
                           List<SourcePath> sourcePaths) {
        public HomeSong(String catalogNumber, String title, String artist,
                        String relativePath, String filePath, Set<String> metadataLocks) {
            this(catalogNumber, title, artist, relativePath, filePath, metadataLocks,
                    List.of(new SourcePath(relativePath, filePath)));
        }

        public HomeSong {
            catalogNumber = catalogNumber == null ? "" : catalogNumber.trim();
            title = title == null ? "" : title;
            artist = artist == null ? "" : artist;
            relativePath = relativePath == null ? "" : relativePath;
            filePath = filePath == null ? "" : filePath;
            metadataLocks = metadataLocks == null ? Set.of() : Set.copyOf(metadataLocks);
            sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
        }
    }

    public record SourcePath(String relativePath, String filePath) {
        public SourcePath {
            relativePath = relativePath == null ? "" : relativePath;
            filePath = filePath == null ? "" : filePath;
        }
    }

    public record ReferenceSong(long referenceSongId, String catalogNumber, String title, String artist,
                                String edition, String language, String category, String type,
                                List<String> specialTags, boolean specialTagConflict) {
        public ReferenceSong {
            catalogNumber = catalogNumber == null ? "" : catalogNumber;
            title = title == null ? "" : title;
            artist = artist == null ? "" : artist;
            edition = edition == null ? "" : edition;
            language = language == null ? "" : language;
            category = category == null ? "" : category;
            type = type == null ? "" : type;
            specialTags = specialTags == null ? List.of() : List.copyOf(specialTags);
        }
    }

    public record Result(Status status, String reason, Method method, ReferenceSong candidate,
                         String suggestedCatalogNumber, int candidateCount) {}
}
