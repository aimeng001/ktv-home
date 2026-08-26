package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.SongFile;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    @Mock
    private PlayerStateRepository playerRepository;
    @Mock
    private QueueItemRepository queueRepository;
    @Mock
    private SongRepository songRepository;
    @Mock
    private PlayHistoryRepository historyRepository;
    @Mock
    private SongFileRepository fileRepository;

    private PlaybackService playbackService;
    private PlayerState playerState;
    private QueueItem currentQueue;

    @BeforeEach
    void setUp() {
        playbackService = new PlaybackService(playerRepository, queueRepository, songRepository,
                historyRepository, fileRepository);

        playerState = new PlayerState();
        playerState.setCurrentQueueId(100L);
        playerState.setState("playing");
        currentQueue = new QueueItem();
        currentQueue.setId(100L);
        currentQueue.setSongId(2L);
        currentQueue.setStatus(QueueService.PLAYING);

        when(playerRepository.getSingleton()).thenReturn(playerState);
        when(playerRepository.save(any(PlayerState.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queueRepository.findById(100L)).thenReturn(Optional.of(currentQueue));
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of());
    }

    @Test
    void playErrorDoesNotInvalidateFileBelongingToAnotherSong() {
        SongFile fileOfAnotherSong = file(200L, 1L);
        when(fileRepository.findById(200L)).thenReturn(Optional.of(fileOfAnotherSong));

        playbackService.onPlayError(200L, 100L);

        assertThat(fileOfAnotherSong.isValid()).isTrue();
    }

    @Test
    void playErrorInvalidatesFileBelongingToCurrentSong() {
        SongFile fileOfCurrentSong = file(201L, 2L);
        when(fileRepository.findById(201L)).thenReturn(Optional.of(fileOfCurrentSong));

        playbackService.onPlayError(201L, 100L);

        assertThat(fileOfCurrentSong.isValid()).isFalse();
    }

    private static SongFile file(long id, long songId) {
        SongFile file = new SongFile();
        file.setId(id);
        file.setSongId(songId);
        file.setValid(true);
        return file;
    }
}
