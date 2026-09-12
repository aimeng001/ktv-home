package com.homektv.web;

import com.homektv.domain.Favorite;
import com.homektv.queue.UserService;
import com.homektv.repo.FavoriteRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 收藏控制器，提供收藏列表查询、添加收藏和移除收藏的 REST API。
 *
 * Favorite controller providing REST APIs for listing, adding, and removing favorites.
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {
    private final FavoriteRepository favoriteRepo;
    private final SongRepository songRepo;
    private final UserService userService;

    public FavoriteController(FavoriteRepository favoriteRepo, SongRepository songRepo, UserService userService) {
        this.favoriteRepo = favoriteRepo;
        this.songRepo = songRepo;
        this.userService = userService;
    }

    /**
     * 查询当前用户的收藏歌曲列表，按收藏时间降序排列。
     *
     * Retrieve the current user's favorite songs, ordered by favorite time descending.
     * @param clientToken 客户端令牌 / client token
     * @return 收藏的歌曲 DTO 列表 / list of favorite song DTOs
     */
    @GetMapping
    public List<SongDto> list(@RequestParam String clientToken) {
        Long userId = requireUser(clientToken);
        List<Long> orderedIds = favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(Favorite::getSongId).filter(Objects::nonNull).toList();
        if (orderedIds.isEmpty()) return List.of();
        Iterable<com.homektv.domain.Song> found = songRepo.findAllById(new LinkedHashSet<>(orderedIds));
        Map<Long, com.homektv.domain.Song> byId = new LinkedHashMap<>();
        if (found != null) found.forEach(song -> byId.put(song.getId(), song));
        return orderedIds.stream().map(byId::get).filter(Objects::nonNull).map(SongDto::from)
                .toList();
    }

    /**
     * 查询当前用户的收藏歌曲 ID 列表，按收藏时间降序排列。
     *
     * Retrieve the current user's favorite song IDs, ordered by favorite time descending.
     * @param clientToken 客户端令牌 / client token
     * @return 收藏的歌曲 ID 列表 / list of favorite song IDs
     */
    @GetMapping("/ids")
    public List<Long> ids(@RequestParam String clientToken) {
        Long userId = requireUser(clientToken);
        return favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(Favorite::getSongId)
                .toList();
    }

    /**
     * 添加歌曲到当前用户的收藏列表。
     *
     * Add a song to the current user's favorites.
     * @param songId 歌曲 ID / song ID
     * @param body 请求体，包含 clientToken / request body containing clientToken
     * @return 包含状态和收藏标记的 Map / map with status and favorite flag
     */
    @PostMapping("/{songId}")
    @Transactional
    public Map<String, Object> add(@PathVariable Long songId, @RequestBody Map<String, String> body) {
        Long userId = requireUser(body.get("clientToken"));
        if (!songRepo.existsById(songId)) throw new ApiException("SONG_NOT_FOUND", "歌曲不存在");
        favoriteRepo.insertIfAbsent(userId, songId);
        return Map.of("status", "ok", "favorite", true);
    }

    /**
     * 从当前用户的收藏列表中移除指定歌曲。
     *
     * Remove a song from the current user's favorites.
     * @param songId 歌曲 ID / song ID
     * @param clientToken 客户端令牌 / client token
     * @return 包含状态和收藏标记的 Map / map with status and favorite flag
     */
    @DeleteMapping("/{songId}")
    @Transactional
    public Map<String, Object> remove(@PathVariable Long songId, @RequestParam String clientToken) {
        Long userId = requireUser(clientToken);
        favoriteRepo.deleteByUserIdAndSongId(userId, songId);
        return Map.of("status", "ok", "favorite", false);
    }

    private Long requireUser(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            throw new ApiException("INVALID_ARGUMENT", "缺少 clientToken");
        }
        return userService.resolveUserId(clientToken);
    }
}
