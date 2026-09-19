package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CategoryBrowseService {
    private static final int PUBLIC_ARTIST_PAGE_SIZE = 30;
    private static final int MAX_PUBLIC_ARTIST_PAGE_SIZE = 100;
    private static final int PUBLIC_SONG_PAGE_SIZE = 50;
    private static final int MAX_PUBLIC_SONG_PAGE_SIZE = 100;
    private final SongRepository songRepository;
    private final SongAvailabilityPolicy availabilityPolicy;

    public CategoryBrowseService(SongRepository songRepository) {
        this(songRepository, null);
    }

    @Autowired
    public CategoryBrowseService(SongRepository songRepository, SongAvailabilityPolicy availabilityPolicy) {
        this.songRepository = songRepository;
        this.availabilityPolicy = availabilityPolicy;
    }

    public List<Map<String, Object>> artists() {
        List<SongRepository.ArtistStatsProjection> rows = songRepository.aggregateArtistsByStatus("ok");
        Map<String, ArtistStats> stats = new HashMap<>();
        for (SongRepository.ArtistStatsProjection row : rows) {
            if (row == null || row.getArtist() == null || row.getArtist().isBlank()) continue;
            String displayName = row.getArtist().trim();
            String artistKey = row.getArtistKey() == null || row.getArtistKey().isBlank()
                    ? ArtistCreditParser.key(displayName) : row.getArtistKey().trim();
            if (artistKey.isBlank()) continue;
            stats.computeIfAbsent(artistKey, ignored -> new ArtistStats(displayName, artistKey)).add(row);
        }
        return stats.values().stream()
                .sorted(Comparator.comparingLong(ArtistStats::count).reversed().thenComparing(ArtistStats::name))
                .map(value -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("artistKey", value.artistKey());
                    item.put("name", value.name());
                    item.put("initial", value.initial());
                    item.put("gender", value.dominantGender());
                    item.put("songCount", (int) value.count());
                    item.put("artistKind", value.artistKind());
                    item.put("avatarUrl", ArtistAvatarUrl.forCachedKey(value.artistKey(), value.avatarPath()));
                    return item;
                })
                .toList();
    }

    /**
     * Public artist directory backed by a bounded database page. The legacy
     * {@link #artists()} endpoint remains available for older clients, while
     * H5 and new clients use this method so a large library is never loaded in
     * one HTTP response.
     */
    public ArtistPage artistsPage(String gender, String initial, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size <= 0 ? PUBLIC_ARTIST_PAGE_SIZE : size,
                MAX_PUBLIC_ARTIST_PAGE_SIZE));
        String safeGender = normalize(gender);
        String safeInitial = normalize(initial).toUpperCase(Locale.ROOT);
        if ("热门".equals(safeInitial)) safeInitial = "";

        Page<SongRepository.PublicArtistDirectoryProjection> rows = songRepository.pagePublicArtistDirectory(
                "ok", safeGender, safeInitial, PageRequest.of(safePage, safeSize));
        List<SongRepository.PublicArtistDirectoryProjection> content = rows == null || rows.getContent() == null
                ? List.of() : rows.getContent();
        List<Map<String, Object>> items = content.stream()
                .filter(Objects::nonNull)
                .map(CategoryBrowseService::publicArtistValue)
                .toList();
        long total = rows == null ? 0 : rows.getTotalElements();
        return new ArtistPage(items, total, safePage, safeSize);
    }

    /** Returns only the small initial-letter index needed by the public UI. */
    public List<String> artistInitials(String gender) {
        List<SongRepository.ArtistInitialProjection> rows = songRepository.findPublicArtistInitials(
                "ok", normalize(gender));
        if (rows == null) return List.of();
        return rows.stream()
                .filter(Objects::nonNull)
                .map(SongRepository.ArtistInitialProjection::getInitial)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    public List<Map<String, Object>> languages() {
        return songRepository.aggregateLanguagesByStatus("ok").stream()
                .map(item -> Map.<String, Object>of("name", item.getLanguage(), "songCount", item.getSongCount()))
                .toList();
    }

    public List<Map<String, Object>> tags() {
        return songRepository.aggregateTagsByStatusOk().stream()
                .map(item -> Map.<String, Object>of("name", item.getName(), "songCount", item.getSongCount()))
                .toList();
    }

    public List<SongDto> songs(String artist, String artistGender, String language, String tag, String vocalForm, String sort, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Sort sortOrder = songSort(sort);
        Page<Song> page = songRepository.browseCategorySongs(
                normalize(artist), normalize(artistGender), normalize(language),
                normalize(tag), normalize(vocalForm),
                PageRequest.of(0, safeLimit, sortOrder));
        return toSongDtos(page.getContent());
    }

    /**
     * Public song browsing with a bounded database page. This prevents an
     * artist with thousands of songs from being materialized in one response.
     */
    public SongPage songsPage(String artist, String artistGender, String language,
                              String tag, String vocalForm, String sort,
                              int page, int size) {
        return songsPage(artist, "", artistGender, language, tag, vocalForm, sort, page, size);
    }

    /** Uses the directory's canonical key so display-name variants are not lost. */
    public SongPage songsPage(String artist, String artistKey, String artistGender, String language,
                              String tag, String vocalForm, String sort,
                              int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(
                size <= 0 ? PUBLIC_SONG_PAGE_SIZE : size, MAX_PUBLIC_SONG_PAGE_SIZE));
        PageRequest request = PageRequest.of(safePage, safeSize, songSort(sort));
        String normalizedKey = ArtistCreditParser.key(artistKey);
        Page<Song> rows = normalizedKey.isBlank()
                ? songRepository.browseCategorySongs(
                        normalize(artist), normalize(artistGender), normalize(language),
                        normalize(tag), normalize(vocalForm), request)
                : songRepository.browseCategorySongsByArtistKey(
                        normalizedKey, normalize(artistGender), normalize(language),
                        normalize(tag), normalize(vocalForm), request);
        List<SongDto> items = rows == null || rows.getContent() == null
                ? List.of() : toSongDtos(rows.getContent());
        return new SongPage(items, rows == null ? 0 : rows.getTotalElements(), safePage, safeSize);
    }

    private List<SongDto> toSongDtos(List<Song> songs) {
        if (songs == null || songs.isEmpty()) return List.of();
        if (availabilityPolicy == null) return songs.stream().map(SongDto::from).toList();
        Set<Long> playableIds = availabilityPolicy.playableSongIds(songs);
        return songs.stream()
                .map(song -> SongDto.from(song, playableIds.contains(song.getId()),
                        playableIds.contains(song.getId()) ? null : SongAvailabilityPolicy.SONG_NOT_READY))
                .toList();
    }

    private static Sort songSort(String sort) {
        return "title".equalsIgnoreCase(sort)
                ? Sort.by(Sort.Direction.ASC, "title")
                : "new".equalsIgnoreCase(sort)
                    ? Sort.by(Sort.Direction.DESC, "created_at")
                            .and(Sort.by(Sort.Direction.DESC, "id"))
                    : Sort.by(Sort.Direction.DESC, "play_count")
                            .and(Sort.by(Sort.Direction.ASC, "title"))
                            .and(Sort.by(Sort.Direction.ASC, "id"));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
    }

    private static Map<String, Object> publicArtistValue(SongRepository.PublicArtistDirectoryProjection row) {
        String name = row.getName() == null || row.getName().isBlank() ? "未知歌手" : row.getName().trim();
        String artistKey = row.getArtistKey() == null || row.getArtistKey().isBlank()
                ? ArtistCreditParser.key(name) : row.getArtistKey();
        String initial = row.getInitial() == null || row.getInitial().isBlank()
                ? PinyinUtil.initials(name) : row.getInitial();
        initial = initial == null || initial.isBlank() ? "#" : initial.substring(0, 1).toUpperCase(Locale.ROOT);
        String kind = effectiveArtistKind(name, row.getArtistKind());
        long count = row.getSongCount() == null ? 0L : row.getSongCount();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("artistKey", artistKey);
        value.put("name", name);
        value.put("initial", initial);
        value.put("gender", row.getGender() == null || row.getGender().isBlank() ? "未知" : row.getGender());
        value.put("songCount", (int) Math.min(Integer.MAX_VALUE, Math.max(0L, count)));
        value.put("artistKind", kind);
        value.put("avatarUrl", ArtistKindClassifier.isPlaceholder(name)
                ? null : ArtistAvatarUrl.forCachedKey(artistKey, row.getAvatarPath()));
        return value;
    }

    private static String effectiveArtistKind(String name, String storedKind) {
        ArtistKind classified = ArtistKindClassifier.classify(name);
        if (classified != ArtistKind.PERSON) return classified.name();
        return storedKind == null || storedKind.isBlank() ? classified.name() : storedKind;
    }

    static String dominantArtistGender(List<Song> songs) {
        return songs.stream().map(Song::getArtistGender)
                .filter(value -> value != null && !value.isBlank() && !"未知".equals(value))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("未知");
    }

    private static final class ArtistStats {
        private final String name;
        private final String artistKey;
        private String artistKind;
        private String avatarPath;
        private String initial;
        private long count;
        private final Map<String, Long> genders = new HashMap<>();

        private ArtistStats(String name, String artistKey) {
            this.name = name;
            this.artistKey = artistKey;
        }

        private void add(SongRepository.ArtistStatsProjection row) {
            if (artistKind == null || artistKind.isBlank()) {
                artistKind = row.getArtistKind();
            }
            if (avatarPath == null || avatarPath.isBlank()) {
                avatarPath = row.getAvatarPath();
            }
            if (initial == null && row.getArtistInit() != null && !row.getArtistInit().isBlank()) {
                initial = row.getArtistInit().substring(0, 1).toUpperCase(Locale.ROOT);
            }
            long rowCount = row.getSongCount() != null ? row.getSongCount() : 0L;
            count += rowCount;
            String gender = row.getArtistGender();
            if (gender != null && !gender.isBlank() && !"未知".equals(gender)) {
                genders.merge(gender, rowCount, Long::sum);
            }
        }

        private String name() { return name; }
        private String artistKey() { return artistKey; }
        private String artistKind() { return effectiveArtistKind(name, artistKind); }
        private String avatarPath() {
            return ArtistKindClassifier.isPlaceholder(name) ? null : avatarPath;
        }
        private String initial() {
            if (initial != null && !initial.isBlank()) {
                return initial.substring(0, 1).toUpperCase(Locale.ROOT);
            }
            String py = PinyinUtil.initials(name);
            return py == null || py.isBlank() ? "#" : py.substring(0, 1).toUpperCase(Locale.ROOT);
        }
        private long count() { return count; }
        private String dominantGender() {
            return genders.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("未知");
        }
    }

    public record ArtistPage(List<Map<String, Object>> items, long total, int page, int size) {}

    public record SongPage(List<SongDto> items, long total, int page, int size) {}
}
