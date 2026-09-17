package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.domain.PlayHistory;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.AudioLayoutSource;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
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

        lenient().when(playerRepository.getSingleton()).thenReturn(playerState);
        lenient().when(playerRepository.getSingletonForUpdate()).thenReturn(playerState);
        lenient().when(playerRepository.save(any(PlayerState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(queueRepository.findById(100L)).thenReturn(Optional.of(currentQueue));
        lenient().when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of());
    }

    @Test
    void playErrorDoesNotInvalidateFileBelongingToAnotherSong() {
        SongFile fileOfAnotherSong = file(200L, 1L);

        playbackService.onPlayError(200L, 100L);

        assertThat(fileOfAnotherSong.isValid()).isTrue();
    }

    @Test
    void clientPlayErrorDoesNotInvalidateOrSaveCurrentFile() {
        SongFile fileOfCurrentSong = file(201L, 2L);

        playbackService.onPlayError(201L, 100L);

        assertThat(fileOfCurrentSong.isValid()).isTrue();
        verify(fileRepository, never()).findById(anyLong());
        verify(fileRepository, never()).save(any(SongFile.class));
    }

    @Test
    void stalePlayErrorDoesNotInvalidateOrAdvanceTheCurrentQueue() {
        PlaybackTransitionResult result = playbackService.onPlayError(202L, 999L);

        assertThat(result.accepted()).isFalse();
        assertThat(result.state()).isSameAs(playerState);
        assertThat(playerState.getCurrentQueueId()).isEqualTo(100L);
        assertThat(currentQueue.getStatus()).isEqualTo(QueueService.PLAYING);
        verify(fileRepository, never()).findById(anyLong());
    }

    @Test
    void stalePositionIsRejectedWithoutSaving() {
        PositionUpdateResult result = playbackService.updatePosition(999L, 12_345L);

        assertThat(result.accepted()).isFalse();
        assertThat(result.state()).isSameAs(playerState);
        assertThat(playerState.getPositionMs()).isZero();
        verify(playerRepository, never()).save(any(PlayerState.class));
    }

    @Test
    void currentPositionIsAcceptedAndSaved() {
        PositionUpdateResult result = playbackService.updatePosition(100L, 12_345L);

        assertThat(result.accepted()).isTrue();
        assertThat(result.state().getPositionMs()).isEqualTo(12_345L);
        verify(playerRepository).save(playerState);
    }

    @Test
    void playSkipsAWaitingSongThatHasNotFinishedMediaProbe() {
        playerState.setCurrentQueueId(null);
        playerState.setState("idle");
        QueueItem pending = new QueueItem();
        pending.setId(101L);
        pending.setSongId(2L);
        pending.setStatus(QueueService.WAITING);
        Song song = new Song();
        song.setId(2L);
        song.setStatus("ok");
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(pending));
        when(songRepository.findAllById(java.util.Set.of(2L))).thenReturn(List.of(song));
        when(fileRepository.findSongIdsWithReadyFile(java.util.Set.of(2L))).thenReturn(java.util.Set.of());

        playbackService.play();

        assertThat(pending.getStatus()).isEqualTo(QueueService.SKIPPED);
        assertThat(playerState.getCurrentQueueId()).isNull();
        assertThat(playerState.getState()).isEqualTo("idle");
    }

    @Test
    void finishedTransitionLocksQueueAndPlayerState() {
        playbackService.onFinished(100L);

        verify(queueRepository).lockQueueMutation();
        verify(playerRepository).getSingletonForUpdate();
    }

    @Test
    void finishedTransitionRecordsHistoryOnceAndTreatsRetryAsAlreadyApplied() {
        Song song = new Song();
        song.setId(2L);
        when(historyRepository.existsByQueueId(100L)).thenReturn(false, true);
        when(songRepository.findById(2L)).thenReturn(Optional.of(song));

        FinishResult first = playbackService.onFinished(100L);
        FinishResult retry = playbackService.onFinished(100L);

        assertThat(first.status()).isEqualTo(FinishResult.Status.APPLIED);
        assertThat(retry.status()).isEqualTo(FinishResult.Status.ALREADY_APPLIED);
        assertThat(currentQueue.getStatus()).isEqualTo(QueueService.DONE);
        assertThat(song.getPlayCount()).isEqualTo(1);
        verify(historyRepository, times(1)).save(any(PlayHistory.class));
    }

    @Test
    void vocalSwapMarksTheFileLayoutAsManual() {
        SongFile file = file(203L, 2L);
        file.setAudioTracks(1);
        file.setAudioLayout(AudioLayout.DUAL_CHANNEL);
        file.setAudioLayoutSource(AudioLayoutSource.AUTO_DEFAULT);
        file.setOriginalChannel(AudioChannel.LEFT);
        file.setAccompanimentChannel(AudioChannel.RIGHT);
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(2L)).thenReturn(List.of(file));

        playbackService.swapVocalTracks();

        assertThat(file.getAudioLayoutSource()).isEqualTo(AudioLayoutSource.MANUAL);
        assertThat(file.getOriginalChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(file.getAccompanimentChannel()).isEqualTo(AudioChannel.LEFT);
        verify(fileRepository).save(file);
    }

    @Test
    void swapVocalTracks_keepsPositionAndSeekSequence() {
        playerState.setPositionMs(12_345L);
        playerState.setSeekSequence(3L);
        SongFile file = file(203L, 2L);
        file.setAudioTracks(1);
        file.setAudioLayout(AudioLayout.DUAL_CHANNEL);
        file.setOriginalChannel(AudioChannel.LEFT);
        file.setAccompanimentChannel(AudioChannel.RIGHT);
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(2L)).thenReturn(List.of(file));

        PlayerState result = playbackService.swapVocalTracks();

        assertThat(result.getPositionMs()).isEqualTo(12_345L);
        assertThat(result.getSeekSequence()).isEqualTo(3L);
    }

    private static SongFile file(long id, long songId) {
        SongFile file = new SongFile();
        file.setId(id);
        file.setSongId(songId);
        file.setValid(true);
        return file;
    }
}
