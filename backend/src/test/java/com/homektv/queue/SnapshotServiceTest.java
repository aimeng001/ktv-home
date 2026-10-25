package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.ws.WsBroadcaster;
import com.homektv.playback.PlaybackResolutionService;
import com.homektv.web.dto.PlaybackDescriptor;
import com.homektv.web.dto.AudioLayoutDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SnapshotServiceTest {

    @Test
    void overlappingSnapshotsCannotGiveCapturedOldStateANewerRevision() throws Exception {
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepository = mock(QueueItemRepository.class);
        SongRepository songRepository = mock(SongRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        WsBroadcaster broadcaster = mock(WsBroadcaster.class);
        AtomicInteger committedVolume = new AtomicInteger(10);
        CountDownLatch firstReadEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRead = new CountDownLatch(1);
        CountDownLatch secondReadEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondSnapshot = new CountDownLatch(1);

        when(playerRepository.getSingleton()).thenAnswer(invocation -> {
            String threadName = Thread.currentThread().getName();
            int capturedVolume;
            if (threadName.equals("snapshot-A")) {
                firstReadEntered.countDown();
                assertThat(releaseFirstRead.await(5, TimeUnit.SECONDS)).isTrue();
                capturedVolume = committedVolume.get();
            } else if (threadName.equals("snapshot-B")) {
                capturedVolume = committedVolume.get();
                secondReadEntered.countDown();
            } else {
                capturedVolume = committedVolume.get();
            }
            PlayerState player = new PlayerState();
            player.setVolume(capturedVolume);
            return player;
        });
        when(queueRepository.countByStatus(QueueService.WAITING)).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("snapshot-B")) {
                assertThat(releaseSecondSnapshot.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return 0L;
        });
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of());
        when(broadcaster.isTvOnline()).thenReturn(false);
        when(broadcaster.h5Count()).thenReturn(0L);

        SnapshotService service = new SnapshotService(playerRepository, queueRepository,
                songRepository, userRepository, broadcaster);
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("snapshot-worker-" + thread.getId());
            return thread;
        });
        try {
            Future<com.homektv.web.dto.QueueSnapshot> first = executor.submit(() -> {
                Thread.currentThread().setName("snapshot-A");
                return service.snapshot();
            });
            assertThat(firstReadEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<com.homektv.web.dto.QueueSnapshot> second = executor.submit(() -> {
                Thread.currentThread().setName("snapshot-B");
                return service.snapshot();
            });
            // On the broken implementation B can capture volume 10 while A is
            // paused. A serialized capture keeps B outside the repositories.
            secondReadEntered.await(2, TimeUnit.SECONDS);
            committedVolume.set(90);
            releaseFirstRead.countDown();

            var newerState = first.get(5, TimeUnit.SECONDS);
            releaseSecondSnapshot.countDown();
            var laterSnapshot = second.get(5, TimeUnit.SECONDS);

            assertThat(newerState.volume()).isEqualTo(90);
            assertThat(laterSnapshot.volume()).isEqualTo(90);
            assertThat(laterSnapshot.stateRevision()).isGreaterThan(newerState.stateRevision());
        } finally {
            releaseFirstRead.countDown();
            releaseSecondSnapshot.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void olderReadThatFinishesLastCannotReceiveANewerRevision() throws Exception {
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepository = mock(QueueItemRepository.class);
        SongRepository songRepository = mock(SongRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        WsBroadcaster broadcaster = mock(WsBroadcaster.class);
        AtomicInteger committedVolume = new AtomicInteger(10);
        CountDownLatch firstReadCaptured = new CountDownLatch(1);
        CountDownLatch releaseFirstSnapshot = new CountDownLatch(1);
        CountDownLatch secondReadCaptured = new CountDownLatch(1);

        when(playerRepository.getSingleton()).thenAnswer(invocation -> {
            String threadName = Thread.currentThread().getName();
            int capturedVolume = committedVolume.get();
            if (threadName.equals("snapshot-A")) firstReadCaptured.countDown();
            if (threadName.equals("snapshot-B")) secondReadCaptured.countDown();
            PlayerState player = new PlayerState();
            player.setVolume(capturedVolume);
            return player;
        });
        when(queueRepository.countByStatus(QueueService.WAITING)).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("snapshot-A")) {
                assertThat(releaseFirstSnapshot.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return 0L;
        });
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of());
        when(broadcaster.isTvOnline()).thenReturn(false);
        when(broadcaster.h5Count()).thenReturn(0L);

        SnapshotService service = new SnapshotService(playerRepository, queueRepository,
                songRepository, userRepository, broadcaster);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<com.homektv.web.dto.QueueSnapshot> first = executor.submit(() -> {
                Thread.currentThread().setName("snapshot-A");
                return service.snapshot();
            });
            assertThat(firstReadCaptured.await(5, TimeUnit.SECONDS)).isTrue();
            committedVolume.set(90);

            Future<com.homektv.web.dto.QueueSnapshot> second = executor.submit(() -> {
                Thread.currentThread().setName("snapshot-B");
                return service.snapshot();
            });
            secondReadCaptured.await(2, TimeUnit.SECONDS);
            releaseFirstSnapshot.countDown();

            var olderRead = first.get(5, TimeUnit.SECONDS);
            var newerRead = second.get(5, TimeUnit.SECONDS);
            assertThat(olderRead.volume()).isEqualTo(10);
            assertThat(newerRead.volume()).isEqualTo(90);
            assertThat(newerRead.stateRevision()).isGreaterThan(olderRead.stateRevision());
        } finally {
            releaseFirstSnapshot.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void snapshotLoadsSongsAndUsersInBatchesInsteadOfOneQueryPerQueueEntry() {
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepository = mock(QueueItemRepository.class);
        SongRepository songRepository = mock(SongRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        WsBroadcaster broadcaster = mock(WsBroadcaster.class);

        PlayerState player = new PlayerState();
        player.setCurrentQueueId(10L);
        QueueItem current = queueItem(10L, 100L, 1L);
        QueueItem waiting = queueItem(11L, 101L, 2L);
        Song currentSong = song(100L, "当前歌曲");
        Song waitingSong = song(101L, "下一首");
        AppUser firstUser = user(1L, "甲");
        AppUser secondUser = user(2L, "乙");

        when(playerRepository.getSingleton()).thenReturn(player);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(waiting));
        when(queueRepository.findById(10L)).thenReturn(java.util.Optional.of(current));
        when(songRepository.findAllById(any())).thenReturn(List.of(currentSong, waitingSong));
        when(userRepository.findAllById(any())).thenReturn(List.of(firstUser, secondUser));
        when(broadcaster.isTvOnline()).thenReturn(false);
        when(broadcaster.h5Count()).thenReturn(0L);

        var snapshot = new SnapshotService(playerRepository, queueRepository, songRepository,
                userRepository, broadcaster).snapshot();

        assertThat(snapshot.playing().song().title()).isEqualTo("当前歌曲");
        assertThat(snapshot.list()).singleElement().extracting(entry -> entry.song().title())
                .isEqualTo("下一首");
        assertThat(snapshot.playing().orderedByNick()).isEqualTo("甲");
        assertThat(snapshot.list().get(0).orderedByNick()).isEqualTo("乙");
        verify(songRepository).findAllById(any());
        verify(userRepository).findAllById(any());
        verify(songRepository, never()).findById(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void ordinaryMp4SnapshotDoesNotResolveSidecar() {
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepository = mock(QueueItemRepository.class);
        SongRepository songRepository = mock(SongRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        WsBroadcaster broadcaster = mock(WsBroadcaster.class);
        SongFileRepository fileRepository = mock(SongFileRepository.class);
        PlaybackResolutionService playbackResolution = mock(PlaybackResolutionService.class);

        PlayerState player = new PlayerState();
        player.setCurrentQueueId(10L);
        QueueItem current = queueItem(10L, 100L, 1L);
        Song currentSong = song(100L, "当前歌曲");
        SongFile source = new SongFile();
        source.setId(700L);
        source.setSongId(100L);
        source.setFilePath("/source-music/song.mp4");
        source.setFormat("mp4");
        source.setMediaType("MV");
        source.setValid(true);
        source.setProbePending(false);

        when(playerRepository.getSingleton()).thenReturn(player);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of());
        when(queueRepository.findById(10L)).thenReturn(java.util.Optional.of(current));
        when(songRepository.findAllById(any())).thenReturn(List.of(currentSong));
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(100L)).thenReturn(List.of(source));
        when(playbackResolution.resolveReady(source, false)).thenReturn(PlaybackDescriptor.nativeSource(source));
        when(broadcaster.isTvOnline()).thenReturn(false);
        when(broadcaster.h5Count()).thenReturn(0L);

        var snapshot = new SnapshotService(playerRepository, queueRepository, songRepository,
                userRepository, broadcaster, fileRepository, playbackResolution).snapshot();

        assertThat(snapshot.playback().kind()).isEqualTo("NATIVE");
        assertThat(snapshot.playback().status()).isEqualTo("READY");
        verify(playbackResolution).resolveReady(source, false);
    }

    @ParameterizedTest
    @MethodSource("readyAudioLayouts")
    void snapshotAudioLayoutComesFromTheSameReadySourceAsPlayback(
            AudioLayoutDto expectedLayout, AudioLayoutDto pendingLayout) {
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepository = mock(QueueItemRepository.class);
        SongRepository songRepository = mock(SongRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        WsBroadcaster broadcaster = mock(WsBroadcaster.class);
        SongFileRepository fileRepository = mock(SongFileRepository.class);
        PlaybackResolutionService playbackResolution = mock(PlaybackResolutionService.class);

        PlayerState player = new PlayerState();
        player.setCurrentQueueId(10L);
        QueueItem current = queueItem(10L, 100L, 1L);
        Song currentSong = song(100L, "当前歌曲");
        SongFile pending = sourceFile(701L, 100, pendingLayout, true);
        SongFile ready = sourceFile(702L, 10, expectedLayout, false);

        when(playerRepository.getSingleton()).thenReturn(player);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of());
        when(queueRepository.findById(10L)).thenReturn(java.util.Optional.of(current));
        when(songRepository.findAllById(any())).thenReturn(List.of(currentSong));
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(100L))
                .thenReturn(List.of(pending, ready));
        when(playbackResolution.resolveReady(ready, false))
                .thenReturn(PlaybackDescriptor.nativeSource(ready));
        when(broadcaster.isTvOnline()).thenReturn(false);
        when(broadcaster.h5Count()).thenReturn(0L);

        var snapshot = new SnapshotService(playerRepository, queueRepository, songRepository,
                userRepository, broadcaster, fileRepository, playbackResolution).snapshot();

        assertThat(snapshot.playback().sourceFileId()).isEqualTo(ready.getId());
        assertThat(snapshot.audioLayout()).isEqualTo(expectedLayout);
        assertThat(snapshot.playback().audioLayout()).isEqualTo(expectedLayout);
        verify(playbackResolution).resolveReady(ready, false);
    }

    @Test
    void pendingSourceDoesNotSupplySnapshotAudioLayoutWhenNoReadyFileExists() {
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepository = mock(QueueItemRepository.class);
        SongRepository songRepository = mock(SongRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        WsBroadcaster broadcaster = mock(WsBroadcaster.class);
        SongFileRepository fileRepository = mock(SongFileRepository.class);
        PlaybackResolutionService playbackResolution = mock(PlaybackResolutionService.class);

        PlayerState player = new PlayerState();
        player.setCurrentQueueId(10L);
        QueueItem current = queueItem(10L, 100L, 1L);
        SongFile pending = sourceFile(701L, 100,
                new AudioLayoutDto(AudioLayout.DUAL_CHANNEL, null, null,
                        AudioChannel.RIGHT, AudioChannel.LEFT), true);
        when(playerRepository.getSingleton()).thenReturn(player);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of());
        when(queueRepository.findById(10L)).thenReturn(java.util.Optional.of(current));
        when(songRepository.findAllById(any())).thenReturn(List.of(song(100L, "当前歌曲")));
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(100L)).thenReturn(List.of(pending));
        when(broadcaster.isTvOnline()).thenReturn(false);
        when(broadcaster.h5Count()).thenReturn(0L);

        var snapshot = new SnapshotService(playerRepository, queueRepository, songRepository,
                userRepository, broadcaster, fileRepository, playbackResolution).snapshot();

        assertThat(snapshot.audioLayout()).isEqualTo(AudioLayoutDto.normalStereo());
        assertThat(snapshot.playback().kind()).isEqualTo("NONE");
        verifyNoInteractions(playbackResolution);
    }

    private static Stream<Arguments> readyAudioLayouts() {
        return Stream.of(
                Arguments.of(
                        new AudioLayoutDto(AudioLayout.DUAL_TRACK, 0, 1,
                                AudioChannel.LEFT, AudioChannel.RIGHT),
                        AudioLayoutDto.normalStereo()),
                Arguments.of(
                        new AudioLayoutDto(AudioLayout.DUAL_CHANNEL, null, null,
                                AudioChannel.RIGHT, AudioChannel.LEFT),
                        AudioLayoutDto.normalStereo()),
                Arguments.of(
                        AudioLayoutDto.normalStereo(),
                        new AudioLayoutDto(AudioLayout.DUAL_CHANNEL, null, null,
                                AudioChannel.RIGHT, AudioChannel.LEFT)));
    }

    private static SongFile sourceFile(Long id, int priority, AudioLayoutDto layout, boolean pending) {
        SongFile source = new SongFile();
        source.setId(id);
        source.setSongId(100L);
        source.setFilePath("/source-music/" + id + ".mkv");
        source.setFormat("mkv");
        source.setMediaType("MV");
        source.setValid(true);
        source.setProbePending(pending);
        source.setPriority(priority);
        source.setAudioLayout(layout.layout());
        source.setOriginalTrackIndex(layout.originalTrackIndex());
        source.setAccompanimentTrackIndex(layout.accompanimentTrackIndex());
        source.setOriginalChannel(layout.originalChannel());
        source.setAccompanimentChannel(layout.accompanimentChannel());
        source.setAudioTracks(layout.layout() == AudioLayout.DUAL_TRACK ? 2 : 1);
        return source;
    }
    private static QueueItem queueItem(long id, long songId, long userId) {
        QueueItem item = new QueueItem();
        item.setId(id);
        item.setSongId(songId);
        item.setOrderedBy(userId);
        item.setStatus(QueueService.WAITING);
        return item;
    }

    private static Song song(long id, String title) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist("歌手");
        return song;
    }

    private static AppUser user(long id, String nickname) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setNickname(nickname);
        return user;
    }
}
