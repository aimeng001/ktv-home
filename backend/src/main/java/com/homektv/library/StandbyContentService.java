package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.musicsource.CoverImageNormalizer;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.SongDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StandbyContentService {
    private final SettingService settingService;
    private final SongRepository songRepository;
    private final PlayHistoryRepository historyRepository;
    private final AssetWriter assetWriter;
    private AssetCleanupService assetCleanupService;
    private CoverImageNormalizer coverImageNormalizer;

    public StandbyContentService(SettingService settingService, SongRepository songRepository,
                                 PlayHistoryRepository historyRepository, AssetWriter assetWriter) {
        this.settingService = settingService;
        this.songRepository = songRepository;
        this.historyRepository = historyRepository;
        this.assetWriter = assetWriter;
    }

    @Autowired
    void setAssetCleanupService(AssetCleanupService assetCleanupService) {
        this.assetCleanupService = assetCleanupService;
    }

    @Autowired
    void setCoverImageNormalizer(CoverImageNormalizer coverImageNormalizer) {
        this.coverImageNormalizer = coverImageNormalizer;
    }

    public Map<String, Object> content() {
        Map<String, Object> settings = settingService.getAll();
        String source = string(settings, "standby_source", "mixed");
        List<Song> songs = switch (source) {
            case "hot" -> hotSongs();
            case "new" -> newSongs();
            case "custom" -> customSongs(settings.get("standby_song_ids"));
            default -> mixedSongs();
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("welcomeText", string(settings, "standby_welcome", "今晚开唱"));
        result.put("subtitle", string(settings, "standby_subtitle", "手机点歌，电视欢唱\n一家人的客厅 KTV"));
        result.put("carouselEnabled", bool(settings, "standby_carousel", true));
        result.put("antiBurn", bool(settings, "anti_burn", true));
        result.put("intervalSeconds", integer(settings, "standby_interval_sec", 8, 3, 60));
        result.put("source", source);
        result.put("videoScaleMode", option(settings, "tv_video_scale_mode", Set.of("fit", "zoom", "fill"), "zoom"));
        result.put("miniQr", bool(settings, "mini_qr", true));
        Object logoPath = settings.get("standby_logo_path");
        result.put("logoUrl", logoPath == null || logoPath.toString().isBlank()
                ? null : "/api/standby/logo");
        result.put("songs", songs.stream().map(SongDto::from).toList());
        return result;
    }

    @Transactional
    public String uploadLogo(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException("INVALID_IMAGE", "请选择 Logo 图片");
        if (file.getSize() > 5 * 1024 * 1024) throw new ApiException("IMAGE_TOO_LARGE", "Logo 不能超过 5MB");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String ext = switch (type) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/jpeg", "image/jpg" -> "jpg";
            default -> throw new ApiException("INVALID_IMAGE", "仅支持 JPG、PNG 或 WebP 图片");
        };
        try {
            Object oldValue = settingService.getAll().get("standby_logo_path");
            String oldPath = oldValue == null ? "" : oldValue.toString();
            byte[] image = file.getBytes();
            String outputExt = ext;
            if (coverImageNormalizer != null) {
                image = normalizeUploadedImage(image);
                outputExt = "jpg";
            }
            String path = assetWriter.writeStandbyLogo(image, outputExt);
            settingService.putInternal("standby_logo_path", path);
            if (assetCleanupService != null) {
                assetCleanupService.afterCommitIfUnreferenced(oldPath);
            }
            return path;
        } catch (IOException e) {
            throw new ApiException("IMAGE_WRITE_FAILED", "Logo 保存失败");
        }
    }

    private byte[] normalizeUploadedImage(byte[] source) {
        if (coverImageNormalizer == null) return source;
        try {
            return coverImageNormalizer.normalize(source);
        } catch (ApiException failure) {
            throw new ApiException("INVALID_IMAGE", "Logo 图片无法识别或尺寸过大");
        }
    }

    private List<Song> hotSongs() {
        List<Long> rankedIds = historyRepository.ranking(OffsetDateTime.now().minusDays(3650), 20).stream()
                .map(row -> row == null || row.length == 0 || !(row[0] instanceof Number number)
                        ? null : number.longValue())
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (rankedIds.isEmpty()) return List.of();

        Map<Long, Song> byId = songRepository.findAllById(rankedIds).stream()
                .filter(this::valid)
                .collect(Collectors.toMap(Song::getId, Function.identity(), (a, b) -> a));
        return rankedIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private List<Song> newSongs() {
        return songRepository.findTop50ByOrderByCreatedAtDesc().stream().filter(this::valid).limit(20).toList();
    }

    private List<Song> mixedSongs() {
        LinkedHashMap<Long, Song> songs = new LinkedHashMap<>();
        hotSongs().forEach(song -> songs.put(song.getId(), song));
        newSongs().forEach(song -> songs.putIfAbsent(song.getId(), song));
        return songs.values().stream().limit(20).toList();
    }

    private List<Song> customSongs(Object value) {
        if (!(value instanceof List<?> ids) || ids.isEmpty()) return List.of();
        List<Long> longIds = ids.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .filter(id -> id > 0)
                .distinct()
                .limit(SettingService.MAX_STANDBY_SONGS)
                .toList();
        if (longIds.isEmpty()) return List.of();

        Map<Long, Song> map = songRepository.findAllById(longIds).stream()
                .filter(this::valid)
                .collect(Collectors.toMap(Song::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        return longIds.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean valid(Song song) { return "ok".equals(song.getStatus()); }
    private String string(Map<String, Object> values, String key, String fallback) { Object value = values.get(key); return value == null || value.toString().isBlank() ? fallback : value.toString(); }
    private boolean bool(Map<String, Object> values, String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean b ? b : value == null ? fallback : Boolean.parseBoolean(value.toString()); }
    private int integer(Map<String, Object> values, String key, int fallback, int min, int max) { Object value = values.get(key); int parsed = value instanceof Number n ? n.intValue() : fallback; return Math.max(min, Math.min(max, parsed)); }
    private String option(Map<String, Object> values, String key, Set<String> allowed, String fallback) { String value = string(values, key, fallback).toLowerCase(Locale.ROOT); return allowed.contains(value) ? value : fallback; }
}
