package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CategoryBrowseService {
    private static final int SONG_PAGE_SIZE = 500;
    private final SongRepository songRepository;

    public CategoryBrowseService(SongRepository songRepository) {
        this.songRepository = songRepository;
    }

    public List<Map<String, Object>> artists() {
        Map<String, ArtistStats> stats = new HashMap<>();
        forEachValidSong(song -> stats.computeIfAbsent(song.getArtist(), ArtistStats::new).add(song));
        return stats.values().stream()
                .sorted(Comparator.comparingInt(ArtistStats::count).reversed().thenComparing(ArtistStats::name))
                .map(value -> Map.<String, Object>of(
                        "name", value.name(),
                        "initial", artistInitial(value.first()),
                        "gender", value.dominantGender(),
                        "songCount", value.count()))
                .toList();
    }

    public List<Map<String, Object>> languages() {
        Map<String, Long> counts = new HashMap<>();
        forEachValidSong(song -> mergeNonBlank(counts, song.getLanguage()));
        return sortedCounts(counts);
    }

    public List<Map<String, Object>> tags() {
        Map<String, Long> counts = new HashMap<>();
        forEachValidSong(song -> {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            if (song.getTags() != null) values.addAll(Arrays.asList(song.getTags()));
            if (song.getAiGenres() != null) values.addAll(Arrays.asList(song.getAiGenres()));
            if (song.getAiThemes() != null) values.addAll(Arrays.asList(song.getAiThemes()));
            values.stream().filter(value -> value != null && !value.isBlank())
                    .forEach(value -> counts.merge(value, 1L, Long::sum));
        });
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(100)
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "songCount", entry.getValue()))
                .toList();
    }

    public List<SongDto> songs(String artist, String artistGender, String language, String tag, String vocalForm, String sort, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Comparator<Song> comparator = "title".equalsIgnoreCase(sort)
                ? Comparator.comparing(Song::getTitle, String.CASE_INSENSITIVE_ORDER)
                : "new".equalsIgnoreCase(sort)
                    ? Comparator.comparing(Song::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    : Comparator.comparingInt(Song::getPlayCount).reversed().thenComparing(Song::getTitle);
        List<Song> topSongs = new ArrayList<>(safeLimit);
        forEachValidSong(song -> {
            if (blank(artist) || song.getArtist().equalsIgnoreCase(artist))
                if (blank(artistGender) || artistGender.equalsIgnoreCase(song.getArtistGender()))
                    if (blank(language) || song.getLanguage().equalsIgnoreCase(language))
                        if (blank(vocalForm) || vocalForm.equalsIgnoreCase(song.getAiVocalForm()))
                                if (blank(tag) || contains(song.getTags(), tag) || contains(song.getAiGenres(), tag)
                                        || contains(song.getAiThemes(), tag)) {
                                    topSongs.add(song);
                                    topSongs.sort(comparator);
                                    if (topSongs.size() > safeLimit) topSongs.remove(topSongs.size() - 1);
                                }
        });
        return topSongs.stream().map(SongDto::from).toList();
    }

    private void forEachValidSong(Consumer<Song> consumer) {
        for (int pageNumber = 0; ; pageNumber++) {
            Page<Song> page = songRepository.findByStatus("ok",
                    PageRequest.of(pageNumber, SONG_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")));
            if (page == null || page.isEmpty()) return;
            page.getContent().stream().filter(Objects::nonNull).forEach(consumer);
            if (!page.hasNext()) return;
        }
    }

    private List<Map<String, Object>> sortedCounts(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "songCount", entry.getValue())).toList();
    }

    private void mergeNonBlank(Map<String, Long> counts, String value) {
        if (value != null && !value.isBlank()) counts.merge(value, 1L, Long::sum);
    }

    private String artistInitial(Song song) {
        return song.getArtistInit() == null || song.getArtistInit().isBlank() ? "#" : song.getArtistInit().substring(0, 1).toUpperCase();
    }

    static String dominantArtistGender(List<Song> songs) {
        return songs.stream().map(Song::getArtistGender)
                .filter(value -> value != null && !value.isBlank() && !"未知".equals(value))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("未知");
    }

    private boolean contains(String[] values, String expected) {
        return values != null && Arrays.stream(values).anyMatch(expected::equalsIgnoreCase);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private static final class ArtistStats {
        private final String name;
        private Song first;
        private int count;
        private final Map<String, Long> genders = new HashMap<>();

        private ArtistStats(String name) { this.name = name; }

        private void add(Song song) {
            if (first == null) first = song;
            count++;
            String gender = song.getArtistGender();
            if (gender != null && !gender.isBlank() && !"未知".equals(gender)) {
                genders.merge(gender, 1L, Long::sum);
            }
        }

        private String name() { return name; }
        private Song first() { return first; }
        private int count() { return count; }
        private String dominantGender() {
            return genders.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("未知");
        }
    }
}
