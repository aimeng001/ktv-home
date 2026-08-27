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
import java.util.List;
import java.util.Map;

@Service
public class PlaylistPublicService {
    private static final int MAX_PLAYLIST_SONGS = 100;
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
        return playlistRepository.findAllByOrderByUpdatedAtDesc().stream()
                .filter(Playlist::isPublicVisible)
                .map(playlist -> {
                    List<PlaylistSong> items = playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlist.getId());
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", playlist.getId());
                    value.put("name", playlist.getName());
                    value.put("description", playlist.getDescription());
                    value.put("theme", playlist.getTheme());
                    value.put("coverUrl", playlist.getCoverPath() == null ? null : "/api/playlists/" + playlist.getId() + "/cover");
                    value.put("aiGenerated", playlist.isAiGenerated());
                    value.put("songCount", items.size());
                    value.put("preview", items.stream().limit(3).map(this::songDto).toList());
                    return value;
                }).toList();
    }

    public Map<String, Object> detail(Long playlistId) {
        Playlist playlist = requirePublic(playlistId);
        List<SongDto> songs = playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlistId).stream()
                .map(this::songDto).filter(java.util.Objects::nonNull).toList();
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
                if ("SONG_IN_QUEUE".equals(exception.getCode()) || "FILE_MISSING".equals(exception.getCode())) skipped++;
                else throw exception;
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

    private SongDto songDto(PlaylistSong item) {
        Song song = songRepository.findById(item.getSongId()).orElse(null);
        return song == null ? null : SongDto.from(song);
    }
}
