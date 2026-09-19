package com.homektv.web;

import com.homektv.domain.Song;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 发现类 API（P3.4）：点唱排行 / 最新入库。供 H5 首页热榜与分类使用。
 *
 * Discovery APIs (P3.4): song ranking and newest arrivals, used by the H5 homepage
 * hot list and category views.
 */
@RestController
@RequestMapping("/api")
public class DiscoveryController {

    private final PlayHistoryRepository historyRepo;
    private final SongRepository songRepo;
    private SongAvailabilityPolicy availabilityPolicy;

    public DiscoveryController(PlayHistoryRepository historyRepo, SongRepository songRepo) {
        this.historyRepo = historyRepo;
        this.songRepo = songRepo;
    }

    @Autowired(required = false)
    void setAvailabilityPolicy(SongAvailabilityPolicy availabilityPolicy) {
        this.availabilityPolicy = availabilityPolicy;
    }

    /**
     * 点唱排行（P3.4）：返回指定天数内的歌曲点唱次数排名，默认近 30 天，取前 20 首。
     *
     * Song ranking (P3.4): returns the top 20 most-requested songs within the given
     * number of days (defaults to 30).
     *
     * @param days 统计天数，默认 30 天 / number of days to look back, defaults to 30
     * @return 排行歌曲列表 / ranked song list
     */
    @GetMapping("/ranking")
    public List<SongDto> ranking(@RequestParam(defaultValue = "30") int days) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(days);
        List<Object[]> rows = historyRepo.ranking(since, 20);
        List<Long> songIds = rows.stream()
                .map(row -> ((Number) row[0]).longValue())
                .distinct()
                .toList();
        if (songIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Song> songMap = songRepo.findAllById(songIds).stream()
                .filter(s -> "ok".equals(s.getStatus()))
                .collect(Collectors.toMap(Song::getId, s -> s));
        List<Song> ordered = new ArrayList<>();
        for (Long id : songIds) {
            Song s = songMap.get(id);
            if (s != null) ordered.add(s);
        }
        return toDtos(ordered);
    }

    /**
     * 最新入库（P3.4）：返回最近新入库的歌曲，按创建时间倒序，取前 50 首。
     *
     * Newest arrivals (P3.4): returns the 50 most-recently added songs, ordered by
     * creation time descending.
     *
     * @return 最新入库歌曲列表 / newest song list
     */
    @GetMapping("/songs/new")
    public List<SongDto> newSongs() {
        return toDtos(songRepo.findTop50ByOrderByCreatedAtDesc().stream()
                .filter(s -> "ok".equals(s.getStatus()))
                .toList());
    }

    private List<SongDto> toDtos(List<Song> songs) {
        if (availabilityPolicy == null || songs.isEmpty()) return songs.stream().map(SongDto::from).toList();
        java.util.Set<Long> playableIds = availabilityPolicy.playableSongIds(songs);
        return songs.stream()
                .map(song -> SongDto.from(song, playableIds.contains(song.getId()),
                        playableIds.contains(song.getId()) ? null : SongAvailabilityPolicy.SONG_NOT_READY))
                .toList();
    }
}
