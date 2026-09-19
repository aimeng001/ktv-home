package com.homektv.musicsource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Song;
import com.homektv.library.ArtistCreditService;
import com.homektv.library.MediaClassifier;
import com.homektv.library.PinyinUtil;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MusicMetadataApplyService {
    private static final Set<String> ALLOWED_FIELDS = Set.of("title", "artist", "album", "releaseDate", "aliases", "cover");
    private final SongRepository songRepository;
    private final MusicSourceSearchService searchService;
    private final ExternalTrackStorage storage;
    private final ExternalCoverService coverService;
    private final MusicSourceConfigService configService;
    private final ObjectMapper mapper;
    private final ArtistCreditService artistCreditService;
    private com.homektv.library.CatalogRevisionService catalogRevisionService;

    @Autowired(required = false)
    void setCatalogRevisionService(com.homektv.library.CatalogRevisionService catalogRevisionService) {
        this.catalogRevisionService = catalogRevisionService;
    }

    public MusicMetadataApplyService(SongRepository songRepository, MusicSourceSearchService searchService,
                                     ExternalTrackStorage storage, ExternalCoverService coverService,
                                     MusicSourceConfigService configService, ObjectMapper mapper) {
        this(songRepository, searchService, storage, coverService, configService, mapper, null);
    }

    @Autowired
    public MusicMetadataApplyService(SongRepository songRepository, MusicSourceSearchService searchService,
                                     ExternalTrackStorage storage, ExternalCoverService coverService,
                                     MusicSourceConfigService configService, ObjectMapper mapper,
                                     ArtistCreditService artistCreditService) {
        this.songRepository = songRepository; this.searchService = searchService; this.storage = storage;
        this.coverService = coverService; this.configService = configService; this.mapper = mapper;
        this.artistCreditService = artistCreditService;
    }

    @Transactional
    public ApplyResult apply(long songId, MusicProvider provider, String externalId, ApplyRequest request) {
        Song song = songRepository.findById(songId).orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        ExternalTrack track = storage.track(provider, externalId, false)
                .orElseGet(() -> searchService.detail(provider, externalId, false));
        Set<String> requested = request == null || request.fields() == null || request.fields().isEmpty()
                ? ALLOWED_FIELDS : new LinkedHashSet<>(request.fields());
        if (!ALLOWED_FIELDS.containsAll(requested)) throw new ApiException("EXTERNAL_FIELDS_INVALID", "包含不支持的应用字段");
        Map<String, String> overrides = sanitizeOverrides(request == null ? null : request.overrides(), requested);
        List<String> applied = new ArrayList<>(); List<String> skippedLocked = new ArrayList<>();
        String suggestedTitle = overrides.getOrDefault("title", track.title());
        String suggestedArtist = overrides.getOrDefault("artist", String.join(" / ", track.artists()));
        boolean structuredArtistCredit = requested.contains("artist") && !overrides.containsKey("artist")
                && !track.artists().isEmpty();
        String nextTitle = requested.contains("title") && (!song.isMetadataLocked("title") || overrides.containsKey("title"))
                && !suggestedTitle.isBlank() ? suggestedTitle : song.getTitle();
        String nextArtist = requested.contains("artist") && (!song.isMetadataLocked("artist") || overrides.containsKey("artist"))
                && !suggestedArtist.isBlank() ? suggestedArtist : song.getArtist();
        String fingerprint = MediaClassifier.fingerprint(nextArtist, nextTitle, song.getDurationMs());
        songRepository.findByFingerprint(fingerprint).filter(other -> !other.getId().equals(songId)).ifPresent(other -> {
            throw new ApiException("SONG_FINGERPRINT_CONFLICT", "应用后会与《" + other.getTitle() + "》重复，请先处理重复歌曲");
        });
        if (requested.contains("title")) applyText(song, "title", suggestedTitle, overrides.containsKey("title"), applied, skippedLocked, value -> {
            song.setTitle(value); song.setTitlePy(PinyinUtil.fullPinyin(value)); song.setTitleInit(PinyinUtil.initials(value));
        });
        if (requested.contains("artist") && !suggestedArtist.isBlank()) applyText(song, "artist", suggestedArtist, overrides.containsKey("artist"), applied, skippedLocked, value -> {
            song.setArtist(value); song.setArtistPy(PinyinUtil.fullPinyin(value)); song.setArtistInit(PinyinUtil.initials(value));
        });
        if (requested.contains("album")) applyText(song, "album", overrides.getOrDefault("album", track.album()), overrides.containsKey("album"), applied, skippedLocked, song::setAlbum);
        if (requested.contains("releaseDate")) applyText(song, "releaseDate", overrides.getOrDefault("releaseDate", track.releaseDate()), overrides.containsKey("releaseDate"), applied, skippedLocked, song::setReleaseDate);
        if (requested.contains("aliases")) {
            if (song.isMetadataLocked("aliases") && !overrides.containsKey("aliases")) skippedLocked.add("aliases");
            else {
                String[] aliases = overrides.containsKey("aliases") ? splitAliases(overrides.get("aliases")) : track.aliases().toArray(String[]::new);
                song.setAliases(aliases); applied.add("aliases");
            }
        }
        song.setFingerprint(fingerprint);
        if (requested.contains("cover") && track.coverUrl() != null) {
            if (song.isMetadataLocked("cover")) skippedLocked.add("cover");
            else {
                int timeout = configService.getConfig().timeoutSeconds();
                song.setCoverPath(coverService.download(provider, track.coverUrl(), fingerprint, Duration.ofSeconds(timeout)));
                applied.add("cover");
            }
        }
        overrides.keySet().stream().filter(applied::contains).forEach(song::lockMetadata);
        updateProvenance(song, provider, externalId, applied, overrides.keySet());
        Song saved = songRepository.save(song);
        if (artistCreditService != null && applied.contains("artist")) {
            if (structuredArtistCredit) artistCreditService.replace(saved.getId(), track.artists());
            else artistCreditService.replace(saved.getId(), saved.getArtist());
        }
        storage.saveMatch(songId, track, 1);
        storage.markApplied(songId, provider, externalId);
        if (catalogRevisionService != null) catalogRevisionService.bumpIfChanged(true);
        return new ApplyResult(song.getId(), song.getTitle(), song.getArtist(), song.getAlbum(), song.getReleaseDate(),
                song.getAliases(), song.getCoverPath(), applied, skippedLocked, track);
    }

    @Transactional
    public ApplyResult applyManual(long songId, ApplyRequest request) {
        Song song = songRepository.findById(songId).orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        Map<String, String> requestedOverrides = request == null || request.overrides() == null ? Map.of() : request.overrides();
        Set<String> requested = request == null || request.fields() == null || request.fields().isEmpty()
                ? new LinkedHashSet<>(requestedOverrides.keySet()) : new LinkedHashSet<>(request.fields());
        Set<String> manualFields = Set.of("title", "artist", "album", "releaseDate", "aliases");
        if (requested.isEmpty() || !manualFields.containsAll(requested))
            throw new ApiException("EXTERNAL_FIELDS_INVALID", "人工填写包含不支持的字段");
        Map<String, String> overrides = sanitizeOverrides(requestedOverrides, requested);
        if (overrides.isEmpty()) throw new ApiException("MANUAL_METADATA_EMPTY", "请至少填写一个元数据字段");

        String nextTitle = overrides.getOrDefault("title", song.getTitle());
        String nextArtist = overrides.getOrDefault("artist", song.getArtist());
        String fingerprint = MediaClassifier.fingerprint(nextArtist, nextTitle, song.getDurationMs());
        songRepository.findByFingerprint(fingerprint).filter(other -> !other.getId().equals(songId)).ifPresent(other -> {
            throw new ApiException("SONG_FINGERPRINT_CONFLICT", "应用后会与《" + other.getTitle() + "》重复，请先处理重复歌曲");
        });

        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (overrides.containsKey("title")) applyText(song, "title", overrides.get("title"), true, applied, skipped, value -> {
            song.setTitle(value); song.setTitlePy(PinyinUtil.fullPinyin(value)); song.setTitleInit(PinyinUtil.initials(value));
        });
        if (overrides.containsKey("artist")) applyText(song, "artist", overrides.get("artist"), true, applied, skipped, value -> {
            song.setArtist(value); song.setArtistPy(PinyinUtil.fullPinyin(value)); song.setArtistInit(PinyinUtil.initials(value));
        });
        if (overrides.containsKey("album")) applyText(song, "album", overrides.get("album"), true, applied, skipped, song::setAlbum);
        if (overrides.containsKey("releaseDate")) applyText(song, "releaseDate", overrides.get("releaseDate"), true, applied, skipped, song::setReleaseDate);
        if (overrides.containsKey("aliases")) { song.setAliases(splitAliases(overrides.get("aliases"))); applied.add("aliases"); }
        song.setFingerprint(fingerprint);
        applied.forEach(song::lockMetadata);
        updateManualProvenance(song, applied);
        Song saved = songRepository.save(song);
        if (artistCreditService != null && applied.contains("artist")) {
            artistCreditService.replace(saved.getId(), saved.getArtist());
        }
        if (!applied.isEmpty() && catalogRevisionService != null) {
            catalogRevisionService.bumpIfChanged(true);
        }
        return new ApplyResult(song.getId(), song.getTitle(), song.getArtist(), song.getAlbum(), song.getReleaseDate(),
                song.getAliases(), song.getCoverPath(), applied, List.of(), null);
    }

    private void applyText(Song song, String field, String value, boolean force, List<String> applied, List<String> skipped,
                           java.util.function.Consumer<String> setter) {
        if (value == null || value.isBlank()) return;
        if (song.isMetadataLocked(field) && !force) skipped.add(field);
        else { setter.accept(value.strip()); applied.add(field); }
    }

    private void updateProvenance(Song song, MusicProvider provider, String externalId, List<String> fields, Set<String> manualFields) {
        try {
            Map<String, Object> provenance = mapper.readValue(song.getMetadataProvenance(), new TypeReference<>() {});
            Map<String, Object> next = new LinkedHashMap<>(provenance);
            for (String field : fields) next.put(field, Map.of("source", manualFields.contains(field) ? "MANUAL_REVIEW" : provider.name(), "externalId", externalId, "trusted", true));
            song.setMetadataProvenance(mapper.writeValueAsString(next));
        } catch (Exception ignored) {
            song.setMetadataProvenance("{}");
        }
    }

    private void updateManualProvenance(Song song, List<String> fields) {
        try {
            Map<String, Object> provenance = mapper.readValue(song.getMetadataProvenance(), new TypeReference<>() {});
            Map<String, Object> next = new LinkedHashMap<>(provenance);
            for (String field : fields) next.put(field, Map.of("source", "MANUAL_REVIEW", "trusted", true));
            song.setMetadataProvenance(mapper.writeValueAsString(next));
        } catch (Exception ignored) {
            song.setMetadataProvenance("{}");
        }
    }

    private static Map<String, String> sanitizeOverrides(Map<String, String> values, Set<String> requested) {
        if (values == null || values.isEmpty()) return Map.of();
        Set<String> editable = Set.of("title", "artist", "album", "releaseDate", "aliases");
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!editable.contains(key) || !requested.contains(key) || value == null) return;
            String clean = value.strip();
            int limit = "aliases".equals(key) ? 1000 : 500;
            if (clean.length() > limit) throw new ApiException("EXTERNAL_OVERRIDE_INVALID", "人工编辑的" + key + "内容过长");
            if (!clean.isEmpty()) result.put(key, clean);
        });
        return result;
    }

    private static String[] splitAliases(String value) {
        return java.util.Arrays.stream(value.split("[\\n,，、]+"))
                .map(String::strip).filter(item -> !item.isEmpty()).distinct().limit(20).toArray(String[]::new);
    }

    public record ApplyRequest(Set<String> fields, Map<String, String> overrides) {
        public ApplyRequest(Set<String> fields) { this(fields, Map.of()); }
    }
    public record ApplyResult(long songId, String title, String artist, String album, String releaseDate,
                              String[] aliases, String coverPath, List<String> appliedFields,
                              List<String> skippedLockedFields, ExternalTrack source) {}
}
