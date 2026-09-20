package com.homektv.queue;

import com.homektv.domain.AppUser;
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
import com.homektv.playback.PlaybackVariantService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotServiceTest {

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
        PlaybackVariantService playbackVariants = mock(PlaybackVariantService.class);

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
        when(playbackVariants.requiresSidecar(source)).thenReturn(false);
        when(broadcaster.isTvOnline()).thenReturn(false);
        when(broadcaster.h5Count()).thenReturn(0L);

        var snapshot = new SnapshotService(playerRepository, queueRepository, songRepository,
                userRepository, broadcaster, fileRepository, playbackVariants).snapshot();

        assertThat(snapshot.playback().kind()).isEqualTo("NATIVE");
        assertThat(snapshot.playback().status()).isEqualTo("READY");
        verify(playbackVariants).requiresSidecar(source);
        verify(playbackVariants, never()).resolve(anyLong());
        verify(playbackVariants, never()).resolve(anyLong(), anyBoolean());
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
