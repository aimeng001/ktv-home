package com.homektv.web;

import com.homektv.domain.AppUser;
import com.homektv.domain.PlayHistory;
import com.homektv.domain.Song;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.QueueService;
import com.homektv.queue.SnapshotService;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryControllerTest {

    @Mock PlayHistoryRepository historyRepository;
    @Mock SongRepository songRepository;
    @Mock AppUserRepository userRepository;

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
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(historyRepository.findTop50ByPlayedByAndPlayedAtGreaterThanEqualOrderByPlayedAtDesc(
                eq(user.getId()), any())).thenReturn(List.of(history));
        when(songRepository.findById(song.getId())).thenReturn(Optional.of(song));

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
    }
}
