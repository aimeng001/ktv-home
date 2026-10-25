package com.homektv.library;

import com.homektv.domain.Playlist;
import com.homektv.domain.PlaylistSong;
import com.homektv.domain.Song;
import com.homektv.queue.*;
import com.homektv.repo.PlaylistRepository;
import com.homektv.repo.PlaylistSongRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.SongDto;
import com.homektv.ws.WsEvent;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlaylistPublicServiceTest {
    private PlaylistRepository playlistRepo;
    private PlaylistSongRepository playlistSongRepo;
    private SongRepository songRepo;
    private QueueService queueService;
    private PlaybackService playbackService;
    private SnapshotService snapshotService;
    private UserService userService;
    private WsBroadcaster broadcaster;
    private PlaylistPublicService service;

    @BeforeEach
    void setUp() {
        playlistRepo = mock(PlaylistRepository.class);
        playlistSongRepo = mock(PlaylistSongRepository.class);
        songRepo = mock(SongRepository.class);
        queueService = mock(QueueService.class);
        playbackService = mock(PlaybackService.class);
        snapshotService = mock(SnapshotService.class);
        userService = mock(UserService.class);
        broadcaster = mock(WsBroadcaster.class);

        service = new PlaylistPublicService(playlistRepo, playlistSongRepo, songRepo,
                queueService, playbackService, snapshotService, userService, broadcaster);

        Playlist pl = new Playlist();
        pl.setId(1L);
        pl.setPublicVisible(true);
        when(playlistRepo.findById(1L)).thenReturn(Optional.of(pl));
        when(userService.resolveUserId("tok")).thenReturn(10L);
        when(snapshotService.snapshot()).thenReturn(new com.homektv.web.dto.QueueSnapshot(null, List.of(), "idle", 50, false, "original", true, 0L));
    }

    @Test
    void orderAll_skipsUnreadyAndMissingSongsAndContinues() {
        PlaylistSong ps1 = new PlaylistSong(); ps1.setSongId(101L);
        PlaylistSong ps2 = new PlaylistSong(); ps2.setSongId(102L);
        PlaylistSong ps3 = new PlaylistSong(); ps3.setSongId(103L);
        PlaylistSong ps4 = new PlaylistSong(); ps4.setSongId(104L);
        when(playlistSongRepo.findByPlaylistIdOrderBySortOrder(1L)).thenReturn(List.of(ps1, ps2, ps3, ps4));

        doThrow(new ApiException(SongAvailabilityPolicy.SONG_NOT_READY, "not ready"))
                .when(queueService).order(102L, 10L, false);
        doThrow(new ApiException("SONG_NOT_FOUND", "missing"))
                .when(queueService).order(103L, 10L, false);

        Map<String, Object> res = service.orderAll(1L, "tok");
        assertThat(res.get("ordered")).isEqualTo(2);
        assertThat(res.get("skipped")).isEqualTo(2);
        verify(queueService).order(104L, 10L, false);
    }

    @Test
    void orderAll_propagatesFatalExceptions() {
        PlaylistSong ps1 = new PlaylistSong(); ps1.setSongId(101L);
        when(playlistSongRepo.findByPlaylistIdOrderBySortOrder(1L)).thenReturn(List.of(ps1));
        doThrow(new ApiException("HOST_REQUIRED", "forbidden")).when(queueService).order(101L, 10L, false);

        assertThatThrownBy(() -> service.orderAll(1L, "tok"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "HOST_REQUIRED");
    }

    @Test
    void orderAllReportsQueueFullAfterKeepingEarlierSuccessfulOrders() {
        PlaylistSong first = new PlaylistSong(); first.setSongId(101L);
        PlaylistSong second = new PlaylistSong(); second.setSongId(102L);
        when(playlistSongRepo.findByPlaylistIdOrderBySortOrder(1L)).thenReturn(List.of(first, second));
        doThrow(new ApiException("QUEUE_FULL", "等待队列已满"))
                .when(queueService).order(102L, 10L, false);

        Map<String, Object> result = service.orderAll(1L, "tok");

        assertThat(result).containsEntry("ordered", 1).containsEntry("queueFull", true);
        verify(queueService).order(101L, 10L, false);
    }

    @Test
    void list_filtersNullPreviewSongsAndPreservesThreeValidSongs() {
        Playlist pl = new Playlist();
        pl.setId(1L);
        pl.setPublicVisible(true);
        when(playlistRepo.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(pl));

        PlaylistSong ps1 = new PlaylistSong(); ps1.setPlaylistId(1L); ps1.setSongId(201L); // Deleted song
        PlaylistSong ps2 = new PlaylistSong(); ps2.setPlaylistId(1L); ps2.setSongId(202L);
        PlaylistSong ps3 = new PlaylistSong(); ps3.setPlaylistId(1L); ps3.setSongId(203L);
        PlaylistSong ps4 = new PlaylistSong(); ps4.setPlaylistId(1L); ps4.setSongId(204L);
        when(playlistSongRepo.findByPlaylistIdInOrderByPlaylistIdAscSortOrderAsc(any()))
                .thenReturn(List.of(ps1, ps2, ps3, ps4));

        com.homektv.domain.Song s2 = new com.homektv.domain.Song(); s2.setId(202L); s2.setTitle("Song 2");
        com.homektv.domain.Song s3 = new com.homektv.domain.Song(); s3.setId(203L); s3.setTitle("Song 3");
        com.homektv.domain.Song s4 = new com.homektv.domain.Song(); s4.setId(204L); s4.setTitle("Song 4");

        when(songRepo.findAllById(any())).thenReturn(List.of(s2, s3, s4));

        List<Map<String, Object>> result = service.list();
        assertThat(result).hasSize(1);
        List<?> preview = (List<?>) result.get(0).get("preview");
        assertThat(preview)
                .hasSize(3)
                .doesNotContainNull();
    }

    @Test
    void detail_loadsSongsInOneBatchAndPreservesPlaylistOrder() {
        Playlist pl = new Playlist();
        pl.setId(1L);
        pl.setPublicVisible(true);
        when(playlistRepo.findById(1L)).thenReturn(Optional.of(pl));
        List<PlaylistSong> rows = IntStream.rangeClosed(1, 100).mapToObj(index -> {
            PlaylistSong row = new PlaylistSong();
            row.setPlaylistId(1L);
            row.setSongId((long) index);
            row.setSortOrder(index);
            return row;
        }).toList();
        when(playlistSongRepo.findByPlaylistIdOrderBySortOrder(1L)).thenReturn(rows);
        List<Song> batchSongs = rows.stream().map(row -> {
            Song song = new Song();
            song.setId(row.getSongId());
            song.setTitle("歌曲" + row.getSongId());
            song.setArtist("歌手");
            song.setMediaType("KTV_VIDEO");
            return song;
        }).toList();
        when(songRepo.findAllById(any())).thenReturn(batchSongs);

        List<?> songs = (List<?>) service.detail(1L).get("songs");

        assertThat(songs).hasSize(100);
        verify(songRepo, times(1)).findAllById(any());
        verify(songRepo, never()).findById(any());
    }

    @Test
    void listHidesPrivatePlaylistsAndSkipsDependentQueriesWhenNoneArePublic() {
        Playlist hidden = new Playlist();
        hidden.setId(2L);
        hidden.setPublicVisible(false);
        when(playlistRepo.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(hidden));

        assertThat(service.list()).isEmpty();

        verify(playlistSongRepo, never()).findByPlaylistIdInOrderByPlaylistIdAscSortOrderAsc(any());
        verifyNoInteractions(songRepo);
    }

    @Test
    void listAndDetailHandleNullRowsAndBuildCoverUrls() {
        Playlist playlist = new Playlist();
        playlist.setId(1L);
        playlist.setName("公开歌单");
        playlist.setPublicVisible(true);
        playlist.setCoverPath("covers/list.jpg");
        when(playlistRepo.findById(1L)).thenReturn(Optional.of(playlist));
        when(playlistRepo.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(playlist));
        when(playlistSongRepo.findByPlaylistIdInOrderByPlaylistIdAscSortOrderAsc(any())).thenReturn(null);
        when(playlistSongRepo.findByPlaylistIdOrderBySortOrder(1L)).thenReturn(null);

        Map<String, Object> listItem = service.list().get(0);
        Map<String, Object> detail = service.detail(1L);

        assertThat(listItem).containsEntry("coverUrl", "/api/playlists/1/cover")
                .containsEntry("songCount", 0);
        assertThat(detail).containsEntry("coverUrl", "/api/playlists/1/cover")
                .containsEntry("songs", List.of());
        verifyNoInteractions(songRepo);
    }

    @Test
    void detailRejectsMissingAndPrivatePlaylists() {
        Playlist privatePlaylist = new Playlist();
        privatePlaylist.setId(3L);
        privatePlaylist.setPublicVisible(false);
        when(playlistRepo.findById(2L)).thenReturn(Optional.empty());
        when(playlistRepo.findById(3L)).thenReturn(Optional.of(privatePlaylist));

        assertThatThrownBy(() -> service.detail(2L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PLAYLIST_NOT_FOUND");
        assertThatThrownBy(() -> service.detail(3L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PLAYLIST_NOT_FOUND");

        verify(playlistSongRepo, never()).findByPlaylistIdOrderBySortOrder(any());
    }

    @Test
    void detailReturnsEmptyWhenSongRepositoryHasNoBatchResult() {
        PlaylistSong row = new PlaylistSong();
        row.setPlaylistId(1L);
        row.setSongId(301L);
        when(playlistSongRepo.findByPlaylistIdOrderBySortOrder(1L)).thenReturn(List.of(row));
        when(songRepo.findAllById(any())).thenReturn(null);

        Map<String, Object> detail = service.detail(1L);

        assertThat(detail.get("songs")).isEqualTo(List.of());
        verify(songRepo).findAllById(Set.of(301L));
    }

    @Test
    void detailUsesSharedReadinessPolicyForPlayableAndPendingSongs() {
        PlaylistSong readyRow = new PlaylistSong();
        readyRow.setPlaylistId(1L);
        readyRow.setSongId(301L);
        PlaylistSong pendingRow = new PlaylistSong();
        pendingRow.setPlaylistId(1L);
        pendingRow.setSongId(302L);
        when(playlistSongRepo.findByPlaylistIdOrderBySortOrder(1L)).thenReturn(List.of(readyRow, pendingRow));

        Song ready = song(301L, "已就绪");
        ready.setStatus("ok");
        Song pending = song(302L, "探测中");
        pending.setStatus("processing");
        when(songRepo.findAllById(any())).thenReturn(List.of(ready, pending));
        SongFileRepository fileRepository = mock(SongFileRepository.class);
        when(fileRepository.findSongIdsWithReadyFile(any())).thenReturn(Set.of(301L));
        service.setAvailabilityPolicy(new SongAvailabilityPolicy(fileRepository));

        @SuppressWarnings("unchecked")
        List<SongDto> songs = (List<SongDto>) service.detail(1L).get("songs");

        assertThat(songs).extracting(SongDto::playable).containsExactly(true, false);
        assertThat(songs.get(0).unavailableReason()).isNull();
        assertThat(songs.get(1).unavailableReason()).isEqualTo(SongAvailabilityPolicy.SONG_NOT_READY);
        verify(fileRepository).findSongIdsWithReadyFile(Set.of(301L));
    }

    @Test
    void orderAllDoesNotStartOrBroadcastWhenEverySongIsSkipped() {
        PlaylistSong row = new PlaylistSong();
        row.setSongId(101L);
        when(playlistSongRepo.findByPlaylistIdOrderBySortOrder(1L)).thenReturn(List.of(row));
        doThrow(new ApiException("FILE_MISSING", "missing source"))
                .when(queueService).order(101L, 10L, false);

        Map<String, Object> result = service.orderAll(1L, "tok");

        assertThat(result).containsEntry("ordered", 0).containsEntry("skipped", 1)
                .containsEntry("queueFull", false);
        verify(playbackService, never()).startIfIdle();
        verifyNoInteractions(broadcaster);
    }

    @Test
    void orderAllBroadcastsNowPlayingOnlyWhenTheNewQueueStartsPlayback() {
        PlaylistSong row = new PlaylistSong();
        row.setSongId(101L);
        when(playlistSongRepo.findByPlaylistIdOrderBySortOrder(1L)).thenReturn(List.of(row));
        when(playbackService.startIfIdle()).thenReturn(true);

        Map<String, Object> result = service.orderAll(1L, "tok");

        assertThat(result).containsEntry("ordered", 1).containsEntry("skipped", 0);
        ArgumentCaptor<WsEvent> eventCaptor = ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, times(2)).broadcastPlayback(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.QUEUE_UPDATED, WsEvent.NOW_PLAYING);
    }

    private static Song song(Long id, String title) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist("测试歌手");
        song.setMediaType("KTV_VIDEO");
        return song;
    }
}
