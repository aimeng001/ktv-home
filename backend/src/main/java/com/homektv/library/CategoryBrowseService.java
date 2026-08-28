package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CategoryBrowseService {
    private final SongRepository songRepository;

    public CategoryBrowseService(SongRepository songRepository) {
        this.songRepository = songRepository;
    }

    public List<Map<String, Object>> artists() {
        List<SongRepository.ArtistStatsProjection> rows = songRepository.aggregateArtistsByStatus("ok");
        Map<String, ArtistStats> stats = new HashMap<>();
        for (SongRepository.ArtistStatsProjection row : rows) {
            if (row == null || row.getArtist() == null || row.getArtist().isBlank()) continue;
            stats.computeIfAbsent(row.getArtist(), ArtistStats::new).add(row);
        }
        return stats.values().stream()
                .sorted(Comparator.comparingLong(ArtistStats::count).reversed().thenComparing(ArtistStats::name))
                .map(value -> Map.<String, Object>of(
                        "name", value.name(),
                        "initial", value.initial(),
                        "gender", value.dominantGender(),
                        "songCount", (int) value.count()))
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
        Sort sortOrder = "title".equalsIgnoreCase(sort)
                ? Sort.by(Sort.Direction.ASC, "title")
                : "new".equalsIgnoreCase(sort)
                    ? Sort.by(Sort.Direction.DESC, "created_at")
                            .and(Sort.by(Sort.Direction.DESC, "id"))
                    : Sort.by(Sort.Direction.DESC, "play_count")
                            .and(Sort.by(Sort.Direction.ASC, "title"))
                            .and(Sort.by(Sort.Direction.ASC, "id"));
        Page<Song> page = songRepository.browseCategorySongs(
                normalize(artist), normalize(artistGender), normalize(language),
                normalize(tag), normalize(vocalForm),
                PageRequest.of(0, safeLimit, sortOrder));
        return page.getContent().stream().map(SongDto::from).toList();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
    }

    static String dominantArtistGender(List<Song> songs) {
        return songs.stream().map(Song::getArtistGender)
                .filter(value -> value != null && !value.isBlank() && !"未知".equals(value))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("未知");
    }

    private static final class ArtistStats {
        private final String name;
        private String initial;
        private long count;
        private final Map<String, Long> genders = new HashMap<>();

        private ArtistStats(String name) { this.name = name; }

        private void add(SongRepository.ArtistStatsProjection row) {
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
}
