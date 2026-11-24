package com.homektv.queue;

import com.homektv.common.PlayerControlChangedEvent;
import com.homektv.common.QueueSnapshotInvalidatedEvent;
import com.homektv.contract.QueueSnapshot;
import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlaybackAndSnapshotRound2CacheTest {

    @Test
    void playbackServiceThrottles1HzPositionWritesAndFlushesOnPauseAndInterval() {
        PlayerStateRepository playerRepo = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepo = mock(QueueItemRepository.class);
        SongRepository songRepo = mock(SongRepository.class);
        PlayHistoryRepository historyRepo = mock(PlayHistoryRepository.class);
        SongFileRepository fileRepo = mock(SongFileRepository.class);

        PlayerState ps = new PlayerState();
        ps.setId(PlayerState.SINGLETON_ID);
        ps.setCurrentQueueId(101L);
        ps.setState("playing");
        ps.setVolume(60);
        ps.setVocalMode("accompaniment");
        ps.setPositionMs(0L);
        when(playerRepo.getSingletonForUpdate()).thenReturn(ps);
        when(playerRepo.save(any(PlayerState.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-24T12:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };

        PlaybackService service = new PlaybackService(playerRepo, queueRepo, songRepo, historyRepo, fileRepo);
        service.setErrorClock(mutableClock);
        service.setPositionPersistIntervalMs(15_000L);

        // First sync for queueId 101 persists to DB (1st save).
        assertThat(service.updatePosition(101L, 1_000L).accepted()).isTrue();
        // Next 9 seconds stay purely in memory: 0 extra DB saves/reads!
        for (int s = 2; s <= 10; s++) {
            now.set(now.get().plusSeconds(1));
            PositionUpdateResult res = service.updatePosition(101L, s * 1_000L);
            assertThat(res.accepted()).isTrue();
            assertThat(res.state().getPositionMs()).isEqualTo(s * 1_000L);
        }
        verify(playerRepo, times(1)).save(any(PlayerState.class));
        assertThat(service.resolveLivePositionMs(101L, "playing", ps.getPositionMs())).isEqualTo(10_000L);

        // Crossing 15s flushes to DB (2nd save).
        now.set(now.get().plusSeconds(6));
        assertThat(service.updatePosition(101L, 16_000L).accepted()).isTrue();
        verify(playerRepo, times(2)).save(any(PlayerState.class));

        // Unflushed in-memory progress at 20s is committed when pause() is invoked (3rd save).
        now.set(now.get().plusSeconds(4));
        assertThat(service.updatePosition(101L, 20_000L).accepted()).isTrue();
        verify(playerRepo, times(2)).save(any(PlayerState.class));
        PlayerState paused = service.pause();
        assertThat(paused.getPositionMs()).isEqualTo(20_000L);
        verify(playerRepo, times(3)).save(any(PlayerState.class));
    }

    @Test
    void snapshotServiceServesCacheWithZeroDbQueriesAndPatchesPlayerControlChanges() {
        PlayerStateRepository playerRepo = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepo = mock(QueueItemRepository.class);
        SongRepository songRepo = mock(SongRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        WsBroadcaster broadcaster = mock(WsBroadcaster.class);
        PlaybackService playbackService = mock(PlaybackService.class);

        PlayerState ps = new PlayerState();
        ps.setCurrentQueueId(10L);
        ps.setState("playing");
        ps.setVolume(60);
        ps.setMuted(false);
        ps.setVocalMode("accompaniment");
        ps.setPositionMs(5_000L);

        QueueItem current = new QueueItem();
        current.setId(10L);
        current.setSongId(100L);
        current.setOrderedBy(1L);
        current.setStatus(QueueService.PLAYING);
        Song song = new Song();
        song.setId(100L);
        song.setTitle("晴天");
        song.setArtist("周杰伦");

        when(playerRepo.getSingleton()).thenReturn(ps);
        when(queueRepo.countByStatus(QueueService.WAITING)).thenReturn(0L);
        when(queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of());
        when(queueRepo.findById(10L)).thenReturn(Optional.of(current));
        when(songRepo.findAllById(any())).thenReturn(List.of(song));
        when(userRepo.findAllById(any())).thenReturn(List.of());
        when(broadcaster.isTvOnline()).thenReturn(true);
        when(broadcaster.connectedPhonesCount()).thenReturn(2L);
        when(playbackService.resolveLivePositionMs(eq(10L), any(), anyLong())).thenReturn(9_500L);

        SnapshotService service = new SnapshotService(playerRepo, queueRepo, songRepo, userRepo, broadcaster);
        service.setPlaybackService(playbackService);
        service.setCacheEnabledForTest(true);

        QueueSnapshot first = service.snapshot();
        QueueSnapshot second = service.snapshot();
        assertThat(first.playing().song().title()).isEqualTo("晴天");
        assertThat(second.positionMs()).isEqualTo(9_500L);
        assertThat(second.stateRevision()).isGreaterThan(first.stateRevision());
        verify(playerRepo, times(1)).getSingleton();
        verify(queueRepo, times(1)).findByStatusOrderByOrderIndexAsc(QueueService.WAITING);

        // Volume/mute/vocal event patches cache with 0 DB queries!
        service.onPlayerControlChanged(new PlayerControlChangedEvent(
                10L, "playing", 75, true, "original", 9_500L, 1L));
        QueueSnapshot patched = service.snapshot();
        assertThat(patched.volume()).isEqualTo(75);
        assertThat(patched.muted()).isTrue();
        assertThat(patched.vocalMode()).isEqualTo("original");
        verify(playerRepo, times(1)).getSingleton();

        // Queue invalidation forces rebuild on next read.
        service.onQueueSnapshotInvalidated(new QueueSnapshotInvalidatedEvent());
        service.snapshot();
        verify(playerRepo, times(2)).getSingleton();
    }
}
