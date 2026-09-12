package com.homektv.library;

import com.homektv.domain.Playlist;
import com.homektv.domain.PlaylistSong;
import com.homektv.domain.Song;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.QueueService;
import com.homektv.queue.SnapshotService;
import com.homektv.queue.UserService;
import com.homektv.repo.PlaylistRepository;
import com.homektv.repo.PlaylistSongRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.web.dto.SongDto;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PlaylistPublicService {
    private static final int MAX_PLAYLIST_SONGS = 100;
    private static final Set<String> SKIPPABLE_ORDER_ERRORS = Set.of(
            "SONG_IN_QUEUE",
            "FILE_MISSING",
            "SONG_NOT_FOUND",
            SongAvailabilityPolicy.SONG_NOT_READY
    );

    private final PlaylistRepository playlistRepository;
    private final PlaylistSongRepository playlistSongRepository;
    private final SongRepository songRepository;
    private final QueueService queueService;
    private final PlaybackService playbackService;
    private final SnapshotService snapshotService;
    private final UserService userService;
    private final WsBroadcaster broadcaster;

    public PlaylistPublicService(PlaylistRepository playlistRepository, PlaylistSongRepository playlistSongRepository,
                                 SongRepository songRepository, QueueService queueService,
                                 PlaybackService playbackService, SnapshotService snapshotService,
                                 UserService userService, WsBroadcaster broadcaster) {
        this.playlistRepository = playlistRepository;
        this.playlistSongRepository = playlistSongRepository;
        this.songRepository = songRepository;
        this.queueService = queueService;
        this.playbackService = playbackService;
        this.snapshotService = snapshotService;
        this.userService = userService;
        this.broadcaster = broadcaster;
    }

    public List<Map<String, Object>> list() {
        List<Playlist> playlists = playlistRepository.findAllByOrderByUpdatedAtDesc().stream()
                .filter(Playlist::isPublicVisible)
                .toList();
        if (playlists.isEmpty()) return List.of();
        List<Long> playlistIds = playlists.stream().map(Playlist::getId).toList();
        List<PlaylistSong> rows = safeRows(playlistSongRepository
                .findByPlaylistIdInOrderByPlaylistIdAscSortOrderAsc(playlistIds));
        Map<Long, List<PlaylistSong>> rowsByPlaylist = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        PlaylistSong::getPlaylistId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<Long, Song> songs = loadSongs(rows.stream().map(PlaylistSong::getSongId).toList());
        return playlists.stream()
                .map(playlist -> {
                    List<PlaylistSong> items = rowsByPlaylist.getOrDefault(playlist.getId(), List.of());
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", playlist.getId());
                    value.put("name", playlist.getName());
                    value.put("description", playlist.getDescription());
                    value.put("theme", playlist.getTheme());
                    value.put("coverUrl", playlist.getCoverPath() == null ? null : "/api/playlists/" + playlist.getId() + "/cover");
                    value.put("aiGenerated", playlist.isAiGenerated());
                    value.put("songCount", items.size());
                    value.put("preview", items.stream().map(item -> songDto(songs.get(item.getSongId())))
                            .filter(Objects::nonNull).limit(3).toList());
                    return value;
                }).toList();
    }

    public Map<String, Object> detail(Long playlistId) {
        Playlist playlist = requirePublic(playlistId);
        List<PlaylistSong> rows = safeRows(playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlistId));
        Map<Long, Song> songById = loadSongs(rows.stream().map(PlaylistSong::getSongId).toList());
        List<SongDto> songs = rows.stream().map(item -> songDto(songById.get(item.getSongId())))
                .filter(Objects::nonNull).toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", playlist.getId());
        value.put("name", playlist.getName());
        value.put("description", playlist.getDescription());
        value.put("theme", playlist.getTheme());
        value.put("coverUrl", playlist.getCoverPath() == null ? null : "/api/playlists/" + playlist.getId() + "/cover");
        value.put("aiGenerated", playlist.isAiGenerated());
        value.put("songs", songs);
        return value;
    }

    public Map<String, Object> orderAll(Long playlistId, String clientToken) {
        requirePublic(playlistId);
        Long userId = userService.resolveUserId(clientToken);
        int ordered = 0;
        int skipped = 0;
        for (PlaylistSong item : playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlistId)) {
            try {
                queueService.order(item.getSongId(), userId, false);
                ordered++;
            } catch (ApiException exception) {
                if (SKIPPABLE_ORDER_ERRORS.contains(exception.getCode())) {
                    skipped++;
                } else {
                    throw exception;
                }
            }
        }
        boolean started = ordered > 0 && playbackService.startIfIdle();
        QueueSnapshot snapshot = snapshotService.snapshot();
        if (ordered > 0) {
            broadcaster.broadcastPlayback(WsEvent.of(WsEvent.QUEUE_UPDATED, snapshot));
            if (started) broadcaster.broadcastPlayback(WsEvent.of(WsEvent.NOW_PLAYING, snapshot));
        }
        return Map.of("ordered", ordered, "skipped", skipped, "snapshot", snapshot);
    }

    @Transactional
    public Map<String, Object> addSong(Long playlistId, Long songId) {
        requirePublic(playlistId);
        if (!songRepository.existsById(songId)) throw new ApiException("SONG_NOT_FOUND", "歌曲不存在");
        playlistSongRepository.lockPlaylist(playlistId);
        List<PlaylistSong> current = playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlistId);
        if (current.stream().anyMatch(item -> item.getSongId().equals(songId))) {
            return Map.of("added", false, "songCount", current.size());
        }
        if (current.size() >= MAX_PLAYLIST_SONGS) {
            throw new ApiException("PLAYLIST_SONG_LIMIT", "每个歌单最多包含 " + MAX_PLAYLIST_SONGS + " 首歌曲");
        }
        int sortOrder = current.stream().mapToInt(PlaylistSong::getSortOrder).max().orElse(-1) + 1;
        playlistSongRepository.insertManualIfAbsent(playlistId, songId, sortOrder);
        return Map.of("added", true, "songCount", current.size() + 1);
    }

    private Playlist requirePublic(Long id) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new ApiException("PLAYLIST_NOT_FOUND", "歌单不存在"));
        if (!playlist.isPublicVisible()) throw new ApiException("PLAYLIST_NOT_FOUND", "歌单不存在或未公开");
        return playlist;
    }

    private List<PlaylistSong> safeRows(List<PlaylistSong> rows) {
        return rows == null ? List.of() : rows;
    }

    private Map<Long, Song> loadSongs(Collection<Long> ids) {
        LinkedHashSet<Long> uniqueIds = ids.stream()
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (uniqueIds.isEmpty()) return Map.of();
        Iterable<Song> found = songRepository.findAllById(uniqueIds);
        if (found == null) return Map.of();
        Map<Long, Song> songs = new LinkedHashMap<>();
        found.forEach(song -> songs.put(song.getId(), song));
        return songs;
    }

    private SongDto songDto(Song song) {
        return song == null ? null : SongDto.from(song);
    }
}
