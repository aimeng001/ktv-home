package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.Playlist;
import com.homektv.library.AssetWriter;
import com.homektv.library.PlaylistPublicService;
import com.homektv.repo.PlaylistRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 歌单控制器，提供歌单列表、详情、点歌和封面等 REST API 接口。
 *
 * Playlist controller providing REST APIs for playlist listing, details, song ordering, and cover images.
 */
@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {
    private final PlaylistPublicService service;
    private final PlaylistRepository playlistRepository;
    private final AssetWriter assetWriter;

    /** Compatibility constructor for test callers providing AppProperties directly. */
    public PlaylistController(PlaylistPublicService service, PlaylistRepository playlistRepository, AppProperties properties) {
        this(service, playlistRepository, new AssetWriter(properties));
    }

    @Autowired
    public PlaylistController(PlaylistPublicService service, PlaylistRepository playlistRepository, AssetWriter assetWriter) {
        this.service = service;
        this.playlistRepository = playlistRepository;
        this.assetWriter = assetWriter;
    }

    /**
     * 获取所有歌单列表。
     *
     * Get all playlist list.
     * @return 歌单列表 / playlist list
     */
    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    /**
     * 获取指定歌单的详细信息。
     *
     * Get detailed information of a specific playlist.
     * @param id 歌单 ID / playlist ID
     * @return 歌单详情 / playlist detail
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.detail(id);
    }

    /**
     * 为指定歌单点播所有歌曲。
     *
     * Order all songs in a specific playlist.
     * @param id 歌单 ID / playlist ID
     * @param request 点歌请求，包含客户端 token / order request containing client token
     * @return 点歌结果 / order result
     */
    @PostMapping("/{id}/order")
    public Map<String, Object> orderAll(@PathVariable Long id, @RequestBody OrderPlaylistRequest request) {
        return service.orderAll(id, request.clientToken());
    }

    @PostMapping("/{id}/songs")
    public Map<String, Object> addSong(@PathVariable Long id, @RequestBody AddSongRequest request) {
        throw new ApiException("PUBLIC_PLAYLIST_READ_ONLY", "公开歌单只能由管理员编辑");
    }

    /**
     * 获取指定歌单的封面图片。
     *
     * Get the cover image of a specific playlist.
     * @param id 歌单 ID / playlist ID
     * @return 封面图片资源，不存在时返回 404 / cover image resource, 404 if not found
     */
    @GetMapping("/{id}/cover")
    public ResponseEntity<Resource> cover(@PathVariable Long id) {
        return playlistRepository.findById(id)
                .filter(Playlist::isPublicVisible)
                .filter(playlist -> playlist.getCoverPath() != null)
                .map(playlist -> serveCover(playlist.getCoverPath()))
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<Resource> serveCover(String relativePath) {
        Path file = assetWriter.readableCachePath(relativePath).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        MediaType type = name.endsWith(".png") ? MediaType.IMAGE_PNG
                : name.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
    }

    public record OrderPlaylistRequest(String clientToken) {}
    public record AddSongRequest(Long songId) {}
}
