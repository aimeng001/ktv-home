package com.homektv.web;

import com.homektv.domain.AppUser;
import com.homektv.domain.PlayHistory;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.QueueService;
import com.homektv.queue.SnapshotService;
import com.homektv.queue.UserService;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.web.dto.SongDto;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HistoryControllerTest {

    @Mock PlayHistoryRepository historyRepository;
    @Mock SongRepository songRepository;
    @Mock AppUserRepository userRepository;
    @Mock QueueService queueService;
    @Mock PlaybackService playbackService;
    @Mock SnapshotService snapshotService;
    @Mock UserService userService;
    @Mock WsBroadcaster broadcaster;
    @Mock SongAvailabilityPolicy availabilityPolicy;

    @Test
    void recentMineQueryFiltersBeforeApplyingTheLimitAndUsesLocalDay() {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setNickname("小明");
        user.setClientToken("client-7");

        Song song = new Song();
        song.setId(11L);
        song.setTitle("晴天");
        song.setArtist("周杰伦");
        song.setMediaType("KTV_VIDEO");

        PlayHistory history = new PlayHistory();
        history.setId(101L);
        history.setSongId(song.getId());
        history.setPlayedBy(user.getId());

        when(userRepository.findByClientToken("client-7")).thenReturn(Optional.of(user));
        when(userRepository.findAllById(any())).thenReturn(List.of(user));
        when(historyRepository.findTop50ByPlayedByAndPlayedAtGreaterThanEqualOrderByPlayedAtDesc(
                eq(user.getId()), any())).thenReturn(List.of(history));
        when(songRepository.findAllById(any())).thenReturn(List.of(song));

        HistoryController controller = new HistoryController(
                historyRepository, songRepository, userRepository,
                null, (PlaybackService) null, (SnapshotService) null,
                null, (WsBroadcaster) null,
                Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneId.of("Asia/Shanghai")));

        assertThat(controller.recent("client-7", true))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.historyId()).isEqualTo(101L);
                    assertThat(item.mine()).isTrue();
                    assertThat(item.song().title()).isEqualTo("晴天");
                });
        verify(songRepository).findAllById(any());
        verify(songRepository, never()).findById(any());
        verify(userRepository).findAllById(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void historyReturnsOnlyRowsWhoseSongsStillExist() {
        when(historyRepository.findTop50ByOrderByPlayedAtDesc()).thenReturn(List.of(
                history(101L, 11L, null), history(102L, 12L, null), history(103L, null, null)));
        when(songRepository.findAllById(any())).thenReturn(List.of(song(11L, "晴天")));

        List<SongDto> result = controller().history();

        assertThat(result).extracting(SongDto::id).containsExactly(11L);
        assertThat(result.getFirst().title()).isEqualTo("晴天");
        verify(songRepository).findAllById(Set.of(11L, 12L));
    }

    @Test
    void historyWithNoEntriesDoesNotQuerySongs() {
        when(historyRepository.findTop50ByOrderByPlayedAtDesc()).thenReturn(List.of());

        assertThat(controller().history()).isEmpty();

        verify(songRepository, never()).findAllById(any());
    }

    @Test
    void recentBlankTokenUsesSharedHistoryAndFallsBackForAnonymousOrMissingUsers() {
        when(historyRepository.findTop50ByPlayedAtGreaterThanEqualOrderByPlayedAtDesc(any()))
                .thenReturn(List.of(
                        history(201L, 11L, null),
                        history(202L, 12L, 99L),
                        history(203L, 404L, 99L)));
        when(songRepository.findAllById(any())).thenReturn(List.of(song(11L, "晴天"), song(12L, "稻香")));
        when(userRepository.findAllById(any())).thenReturn(null);

        List<com.homektv.web.dto.RecentHistoryDto> result = controller().recent(" \t ", false);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(com.homektv.web.dto.RecentHistoryDto::playedByNick)
                .containsExactly("家人", "家人");
        assertThat(result).allSatisfy(item -> assertThat(item.mine()).isFalse());
        verify(userRepository, never()).findByClientToken(any());
        verify(userRepository).findAllById(Set.of(99L));
    }

    @Test
    void recentMineWithoutResolvableTokenReturnsEmptyWithoutReadingHistory() {
        when(userRepository.findByClientToken("missing-token")).thenReturn(Optional.empty());

        assertThat(controller().recent("missing-token", true)).isEmpty();

        verify(historyRepository, never()).findTop50ByPlayedByAndPlayedAtGreaterThanEqualOrderByPlayedAtDesc(
                any(), any());
        verify(historyRepository, never()).findTop50ByPlayedAtGreaterThanEqualOrderByPlayedAtDesc(any());
        verify(songRepository, never()).findAllById(any());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void recentWithNullSongRepositoryResultSkipsUnavailableRows() {
        when(historyRepository.findTop50ByPlayedAtGreaterThanEqualOrderByPlayedAtDesc(any()))
                .thenReturn(List.of(history(301L, 11L, null)));
        when(songRepository.findAllById(any())).thenReturn(null);

        assertThat(controller().recent(null, false)).isEmpty();

        verify(userRepository, never()).findAllById(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dtoConversionTreatsNullCollectionsAndNullSongsAsEmpty() throws Exception {
        Method converter = HistoryController.class.getDeclaredMethod("toDtos", Iterable.class);
        converter.setAccessible(true);
        HistoryController controller = controller();

        Map<Long, SongDto> nullCollection = (Map<Long, SongDto>) converter.invoke(controller, new Object[]{null});
        Map<Long, SongDto> nullSong = (Map<Long, SongDto>) converter.invoke(
                controller, Collections.singletonList(null));

        assertThat(nullCollection).isEmpty();
        assertThat(nullSong).isEmpty();
    }

    @Test
    void nonMineRecentUsesResolvedUserForOwnershipAndReadiness() {
        AppUser currentUser = user(7L, "小明", "client-7");
        when(userRepository.findByClientToken("client-7")).thenReturn(Optional.of(currentUser));
        when(historyRepository.findTop50ByPlayedAtGreaterThanEqualOrderByPlayedAtDesc(any()))
                .thenReturn(List.of(history(401L, 11L, 7L), history(402L, 12L, 8L)));
        when(songRepository.findAllById(any())).thenReturn(List.of(song(11L, "晴天"), song(12L, "稻香")));
        when(userRepository.findAllById(any())).thenReturn(List.of(currentUser));
        when(availabilityPolicy.playableSongIds(any())).thenReturn(Set.of(11L));
        HistoryController controller = controller();
        controller.setAvailabilityPolicy(availabilityPolicy);

        List<com.homektv.web.dto.RecentHistoryDto> result = controller.recent("client-7", false);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).mine()).isTrue();
        assertThat(result.get(1).mine()).isFalse();
        assertThat(result.get(0).song().playable()).isTrue();
        assertThat(result.get(1).song().playable()).isFalse();
        assertThat(result.get(1).song().unavailableReason()).isEqualTo(SongAvailabilityPolicy.SONG_NOT_READY);
        verify(historyRepository).findTop50ByPlayedAtGreaterThanEqualOrderByPlayedAtDesc(any());
        verify(historyRepository, never()).findTop50ByPlayedByAndPlayedAtGreaterThanEqualOrderByPlayedAtDesc(
                any(), any());
    }

    @Test
    void repeatReordersHistoryAndBroadcastsQueueAndNowPlayingWhenPlaybackStarts() {
        PlayHistory history = history(501L, 11L, 7L);
        QueueItem item = new QueueItem();
        item.setId(801L);
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "playing", 60, false,
                "original", true, 0L);
        when(historyRepository.findById(501L)).thenReturn(Optional.of(history));
        when(userService.resolveUserId("client-7")).thenReturn(7L);
        when(queueService.order(11L, 7L, true)).thenReturn(item);
        when(queueService.waitingPosition(item)).thenReturn(2);
        when(playbackService.startIfIdle()).thenReturn(true);
        when(snapshotService.snapshot()).thenReturn(snapshot);

        var result = controller().repeat(501L, new HistoryController.RepeatRequest("client-7", true));

        assertThat(result).containsEntry("queueId", 801L).containsEntry("position", 2)
                .containsEntry("snapshot", snapshot);
        ArgumentCaptor<WsEvent> events = ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).broadcastPlayback(events.capture());
        assertThat(events.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.QUEUE_UPDATED, WsEvent.NOW_PLAYING);
    }

    @Test
    void repeatOnlyBroadcastsQueueWhenPlaybackDoesNotStart() {
        PlayHistory history = history(502L, 12L, null);
        QueueItem item = new QueueItem();
        item.setId(802L);
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "idle", 60, false,
                "original", true, 0L);
        when(historyRepository.findById(502L)).thenReturn(Optional.of(history));
        when(userService.resolveUserId("anonymous")).thenReturn(null);
        when(queueService.order(12L, null, false)).thenReturn(item);
        when(queueService.waitingPosition(item)).thenReturn(1);
        when(playbackService.startIfIdle()).thenReturn(false);
        when(snapshotService.snapshot()).thenReturn(snapshot);

        controller().repeat(502L, new HistoryController.RepeatRequest("anonymous", false));

        verify(broadcaster).broadcastPlayback(org.mockito.ArgumentMatchers.argThat(
                event -> event.type().equals(WsEvent.QUEUE_UPDATED)));
        verify(broadcaster, never()).broadcastPlayback(org.mockito.ArgumentMatchers.argThat(
                event -> event.type().equals(WsEvent.NOW_PLAYING)));
    }

    @Test
    void repeatOfMissingHistoryFailsBeforeQueueSideEffects() {
        when(historyRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().repeat(999L, new HistoryController.RepeatRequest("token", false)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("HISTORY_NOT_FOUND");

        verify(userService, never()).resolveUserId(any());
        verify(queueService, never()).order(any(), any(), anyBoolean());
        verify(playbackService, never()).startIfIdle();
        verify(snapshotService, never()).snapshot();
        verify(broadcaster, never()).broadcastPlayback(any());
    }

    private HistoryController controller() {
        return new HistoryController(historyRepository, songRepository, userRepository,
                queueService, playbackService, snapshotService, userService, broadcaster,
                Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    private static PlayHistory history(Long id, Long songId, Long playedBy) {
        PlayHistory history = new PlayHistory();
        history.setId(id);
        history.setSongId(songId);
        history.setPlayedBy(playedBy);
        return history;
    }

    private static Song song(Long id, String title) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist("周杰伦");
        song.setMediaType("KTV_VIDEO");
        return song;
    }

    private static AppUser user(Long id, String nickname, String clientToken) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setNickname(nickname);
        user.setClientToken(clientToken);
        return user;
    }
}
