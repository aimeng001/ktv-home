package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.library.AssetWriter;
import com.homektv.library.MediaClassifier;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.library.SongSearchService;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDetailDto;
import com.homektv.web.dto.SongDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 歌曲搜索/详情/资源 API（P1.6/P1.7，详设§11.1）。
 *
 * Song search / detail / resource API (P1.6/P1.7, detailed design §11.1).
 */
@RestController
@RequestMapping("/api")
public class SongController {

    private final SongSearchService searchService;
    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final AssetWriter assetWriter;
    private SongAvailabilityPolicy availabilityPolicy;

    /** Compatibility constructor for test callers providing AppProperties directly. */
    public SongController(SongSearchService searchService, SongRepository songRepo,
                          SongFileRepository fileRepo, AppProperties props) {
        this(searchService, songRepo, fileRepo, new AssetWriter(props));
    }

    @Autowired
    public SongController(SongSearchService searchService, SongRepository songRepo,
                          SongFileRepository fileRepo, AssetWriter assetWriter) {
        this.searchService = searchService;
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.assetWriter = assetWriter;
    }

    @Autowired(required = false)
    void setAvailabilityPolicy(SongAvailabilityPolicy availabilityPolicy) {
        this.availabilityPolicy = availabilityPolicy;
    }

    /**
     * 综合搜索（P1.6）：keyword/acronym 均支持中文/全拼/首字母，acronym 为电视端简拼查询别名。
     *
     * Combined search (P1.6): keyword supports Chinese characters, full pinyin, or initials.
     * @param keyword 搜索关键词 / search keyword
     * @param acronym 电视端简拼关键词 / TV kiosk acronym query
     * @param type    媒体类型过滤（可选）/ media type filter (optional)
     * @param page    分页页码 / page number
     * @return 匹配的歌曲列表 / list of matching songs
     */
    @GetMapping("/songs")
    public List<SongDto> search(@RequestParam(defaultValue = "") String keyword,
                                @RequestParam(defaultValue = "") String acronym,
                                @RequestParam(defaultValue = "") String type,
                                @RequestParam(defaultValue = "0") int page) {
        String searchTerm = acronym.isBlank() ? keyword : acronym;
        List<Song> songs = searchService.search(searchTerm, type, page);
        if (songs.isEmpty()) return List.of();
        Map<Long, String> resolutions = storedVideoResolutions(songs);
        if (availabilityPolicy == null) {
            return songs.stream()
                    .map(song -> SongDto.from(song, true, null, resolutions.get(song.getId())))
                    .toList();
        }
        java.util.Set<Long> playableIds = availabilityPolicy.playableSongIds(songs);
        return songs.stream()
                .map(song -> SongDto.from(song, playableIds.contains(song.getId()),
                        playableIds.contains(song.getId()) ? null : SongAvailabilityPolicy.SONG_NOT_READY,
                        resolutions.get(song.getId())))
                .toList();
    }

    /** Reads resolution only for the current search page; this never opens media files. */
    private Map<Long, String> storedVideoResolutions(List<Song> songs) {
        if (fileRepo == null || songs == null || songs.isEmpty()) return Map.of();
        Set<Long> songIds = new LinkedHashSet<>();
        songs.stream()
                .filter(Objects::nonNull)
                .map(Song::getId)
                .filter(Objects::nonNull)
                .forEach(songIds::add);
        if (songIds.isEmpty()) return Map.of();

        List<SongFile> files = fileRepo.findBySongIdInAndValidTrueOrderByPriorityDesc(songIds);
        if (files == null || files.isEmpty()) return Map.of();

        Map<Long, String> resolutions = new LinkedHashMap<>();
        for (SongFile file : files) {
            if (!SongAvailabilityPolicy.isReadyMediaFile(file)) continue;
            String mediaType = file.getMediaType();
            if (!MediaClassifier.MV.equalsIgnoreCase(mediaType)
                    && !MediaClassifier.KTV_VIDEO.equalsIgnoreCase(mediaType)) continue;
            String resolution = file.getResolution();
            if (resolution == null || resolution.isBlank()) continue;
            resolutions.putIfAbsent(file.getSongId(), resolution.trim());
        }
        return resolutions;
    }

    /** Keeps existing in-process callers source compatible with the original keyword endpoint. */
    public List<SongDto> search(String keyword, String type, int page) {
        return search(keyword, "", type, page);
    }

    /**
     * 歌曲详情（P1.7）。
     *
     * Song detail (P1.7).
     * @param id 歌曲ID / song ID
     * @return 歌曲详情，包含关联的有效文件列表 / song detail with associated valid file list
     */
    @GetMapping("/songs/{id}")
    public ResponseEntity<SongDetailDto> detail(@PathVariable Long id) {
        return songRepo.findById(id)
                .map(s -> ResponseEntity.ok(
                        SongDetailDto.from(s, fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(id))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 封面资源（P1.7）。
     *
     * Cover image resource (P1.7).
     * @param id 歌曲ID / song ID
     * @return 封面图片资源 / cover image resource
     */
    @GetMapping("/cover/{id}")
    public ResponseEntity<Resource> cover(@PathVariable Long id) {
        return songRepo.findById(id)
                .filter(s -> s.getCoverPath() != null)
                .map(s -> serveFile(s.getCoverPath(), guessImageType(s.getCoverPath())))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 歌词资源（P1.7）：返回原始歌词文本（LRC/KSC）；JSON 时间轴解析留 P1.26。
     *
     * Lyric resource (P1.7): returns raw lyric text (LRC/KSC); JSON timeline parsing deferred to P1.26.
     * @param id 歌曲ID / song ID
     * @return 歌词文本资源 / lyric text resource
     */
    @GetMapping(value = "/lyric/{id}", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<Resource> lyric(@PathVariable Long id) {
        return songRepo.findById(id)
                .filter(s -> s.getLyricPath() != null)
                .map(s -> serveFile(s.getLyricPath(), MediaType.TEXT_PLAIN))
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<Resource> serveFile(String relPath, MediaType fallbackType) {
        Path file = assetWriter.readableCachePath(relPath).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(fallbackType).body(new FileSystemResource(file));
    }

    private static MediaType guessImageType(String path) {
        if (path == null) return MediaType.IMAGE_JPEG;
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (p.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }
}
