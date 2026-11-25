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
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void legacyPlayErrorWithoutExpectedQueueIdentityStillAdvancesCurrentItem() {
        PlaybackTransitionResult result = playbackService.onPlayError(201L);

        assertThat(result.accepted()).isTrue();
        assertThat(currentQueue.getStatus()).isEqualTo(QueueService.SKIPPED);
        assertThat(playerState.getCurrentQueueId()).isNull();
    }

    @Test
    void consecutivePlayErrorsInShortWindowTriggerCircuitBreakerAndPauseQueue() {
        QueueItem second = queueItem(101L, QueueService.WAITING);
        QueueItem third = queueItem(102L, QueueService.WAITING);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(second, third), List.of(third));
        when(songRepository.findAllById(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(song(102L), song(103L)), List.of(song(103L)));
        when(fileRepository.findSongIdsWithReadyFile(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(java.util.Set.of(102L, 103L), java.util.Set.of(103L));
        when(queueRepository.findById(101L)).thenReturn(Optional.of(second));

        PlaybackTransitionResult firstError = playbackService.onPlayError(201L, 100L);
        assertThat(firstError.circuitBroken()).isFalse();
        assertThat(playerState.getCurrentQueueId()).isEqualTo(101L);

        PlaybackTransitionResult secondError = playbackService.onPlayError(202L, 101L);
        assertThat(secondError.circuitBroken()).isFalse();
        assertThat(playerState.getCurrentQueueId()).isEqualTo(102L);

        PlaybackTransitionResult thirdError = playbackService.onPlayError(203L, 102L);
        assertThat(thirdError.accepted()).isTrue();
        assertThat(thirdError.circuitBroken()).isTrue();
        assertThat(playerState.getState()).isEqualTo("paused");
        assertThat(playerState.getCurrentQueueId()).isEqualTo(102L);
        assertThat(third.getStatus()).isEqualTo(QueueService.PLAYING);
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
    void playerPositionRejectsIdleReportsAndKeepsUnchangedClampedPositionWithoutSaving() {
        playerState.setState("idle");
        PositionUpdateResult idle = playbackService.updatePosition(null, 12_345L);
        assertThat(idle.accepted()).isFalse();

        playerState.setState("playing");
        PositionUpdateResult unchanged = playbackService.updatePosition(null, -5L);

        assertThat(unchanged.accepted()).isTrue();
        assertThat(unchanged.state().getPositionMs()).isZero();
        verify(playerRepository, never()).save(any(PlayerState.class));
    }

    @Test
    void explicitSeekRejectsNegativePositionsAndMissingCurrentSong() {
        assertThatThrownBy(() -> playbackService.seek(-1L))
                .isInstanceOf(com.homektv.common.ApiException.class)
                .hasMessageContaining("不能为负数");

        playerState.setCurrentQueueId(null);
        assertThatThrownBy(() -> playbackService.seek(0L))
                .isInstanceOf(com.homektv.common.ApiException.class)
                .hasMessageContaining("当前没有正在播放的歌曲");
        verify(playerRepository, never()).save(any(PlayerState.class));
    }

    @Test
    void completionReportsAreStaleOrAlreadyAppliedWhenTheCurrentQueueDoesNotMatch() {
        assertThat(playbackService.onFinished(null).status()).isEqualTo(FinishResult.Status.STALE);

        when(historyRepository.existsByQueueId(999L)).thenReturn(false, true);
        assertThat(playbackService.onFinished(999L).status()).isEqualTo(FinishResult.Status.STALE);
        assertThat(playbackService.onFinished(999L).status()).isEqualTo(FinishResult.Status.ALREADY_APPLIED);

        when(queueRepository.findById(100L)).thenReturn(Optional.empty());
        when(historyRepository.existsByQueueId(100L)).thenReturn(false, true);
        assertThat(playbackService.onFinished(100L).status()).isEqualTo(FinishResult.Status.STALE);
        assertThat(playbackService.onFinished(100L).status()).isEqualTo(FinishResult.Status.ALREADY_APPLIED);

        when(queueRepository.findById(100L)).thenReturn(Optional.of(currentQueue));
        currentQueue.setStatus(QueueService.WAITING);
        when(historyRepository.existsByQueueId(100L)).thenReturn(false);
        assertThat(playbackService.onFinished(100L).status()).isEqualTo(FinishResult.Status.STALE);
        verify(historyRepository, never()).save(any(PlayHistory.class));
    }

    @Test
    void idleAutostartDoesNotResumePausedPlaybackAndReturnsFalseForAnEmptyQueue() {
        playerState.setCurrentQueueId(null);
        playerState.setState("paused");

        assertThat(playbackService.startIfIdle()).isFalse();
        assertThat(playerState.getState()).isEqualTo("paused");

        playerState.setState("idle");
        assertThat(playbackService.startIfIdle()).isFalse();
        assertThat(playerState.getCurrentQueueId()).isNull();
    }

    @Test
    void recoveryUsesTheFirstPlayingQueueAndReturnsOtherPlayingRowsToWaiting() {
        currentQueue.setStatus(QueueService.DONE);
        QueueItem firstPlaying = queueItem(201L, QueueService.PLAYING);
        QueueItem duplicatePlaying = queueItem(202L, QueueService.PLAYING);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.PLAYING))
                .thenReturn(List.of(firstPlaying, duplicatePlaying));

        PlayerState recovered = playbackService.recoverAfterRestart();

        assertThat(recovered.getCurrentQueueId()).isEqualTo(201L);
        assertThat(recovered.getState()).isEqualTo("playing");
        assertThat(firstPlaying.getStatus()).isEqualTo(QueueService.PLAYING);
        assertThat(duplicatePlaying.getStatus()).isEqualTo(QueueService.WAITING);
        verify(queueRepository).save(duplicatePlaying);
    }

    @Test
    void tvOfflineKeepsAnEmptyIdlePlayerButClearsCurrentAndWaitingRowsWhenPresent() {
        playerState.setCurrentQueueId(null);
        playerState.setState("idle");
        assertThat(playbackService.clearOnTvOffline()).isFalse();

        playerState.setCurrentQueueId(100L);
        QueueItem waiting = queueItem(101L, QueueService.WAITING);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(waiting));

        assertThat(playbackService.clearOnTvOffline()).isTrue();
        assertThat(currentQueue.getStatus()).isEqualTo(QueueService.SKIPPED);
        assertThat(playerState.getCurrentQueueId()).isNull();
        assertThat(playerState.getPositionMs()).isZero();
        verify(queueRepository).deleteAll(List.of(waiting));
        verify(historyRepository, never()).save(any(PlayHistory.class));
    }

    @Test
    void volumeIsClampedAndVocalModeRejectsUnknownValues() {
        assertThat(playbackService.setVolume(-1).getVolume()).isZero();
        assertThat(playbackService.setVolume(101).getVolume()).isEqualTo(100);
        assertThatThrownBy(() -> playbackService.setVocalMode("stereo"))
                .isInstanceOf(com.homektv.common.ApiException.class)
                .hasMessageContaining("无效的原伴唱模式");
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

    @Test
    void playResumesCurrentItemAndStartsTheFirstReadyWaitingItem() {
        PlayerState resumed = playbackService.play();
        assertThat(resumed.getState()).isEqualTo("playing");
        verify(queueRepository, never()).findByStatusOrderByOrderIndexAsc(QueueService.WAITING);

        playerState.setCurrentQueueId(null);
        playerState.setState("idle");
        QueueItem waiting = queueItem(101L, QueueService.WAITING);
        Song song = song(waiting.getSongId());
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of(waiting));
        when(songRepository.findAllById(java.util.Set.of(song.getId()))).thenReturn(List.of(song));
        when(fileRepository.findSongIdsWithReadyFile(java.util.Set.of(song.getId())))
                .thenReturn(java.util.Set.of(song.getId()));

        PlayerState started = playbackService.play();

        assertThat(started.getCurrentQueueId()).isEqualTo(waiting.getId());
        assertThat(started.getState()).isEqualTo("playing");
        assertThat(waiting.getStatus()).isEqualTo(QueueService.PLAYING);
    }

    @Test
    void idleAutostartBeginsReadyQueueAndRefusesAnAlreadyOccupiedPlayer() {
        playerState.setCurrentQueueId(null);
        playerState.setState("idle");
        QueueItem waiting = queueItem(101L, QueueService.WAITING);
        Song song = song(waiting.getSongId());
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of(waiting));
        when(songRepository.findAllById(java.util.Set.of(song.getId()))).thenReturn(List.of(song));
        when(fileRepository.findSongIdsWithReadyFile(java.util.Set.of(song.getId())))
                .thenReturn(java.util.Set.of(song.getId()));

        assertThat(playbackService.startIfIdle()).isTrue();
        assertThat(playerState.getCurrentQueueId()).isEqualTo(waiting.getId());

        assertThat(playbackService.startIfIdle()).isFalse();
        verify(queueRepository, times(1)).findByStatusOrderByOrderIndexAsc(QueueService.WAITING);
    }

    @Test
    void pauseChangesOnlyPlayingStateAndSeekUpdatesPositionSequence() {
        playerState.setState("playing");
        assertThat(playbackService.pause().getState()).isEqualTo("paused");
        playerState.setState("idle");
        assertThat(playbackService.pause().getState()).isEqualTo("idle");

        playerState.setCurrentQueueId(100L);
        playerState.setSeekSequence(4L);
        PlayerState sought = playbackService.seek(9_876L);

        assertThat(sought.getPositionMs()).isEqualTo(9_876L);
        assertThat(sought.getSeekSequence()).isEqualTo(5L);
        verify(playerRepository, times(3)).save(playerState);
    }

    @Test
    void positionUpdateRejectsMissingCurrentQueueEvenWhenTheStateIsNotIdle() {
        playerState.setCurrentQueueId(null);
        playerState.setState("playing");

        PositionUpdateResult result = playbackService.updatePosition(null, 10L);

        assertThat(result.accepted()).isFalse();
        verify(playerRepository, never()).save(any(PlayerState.class));
    }

    @Test
    void restartRequiresAndResetsTheCurrentQueueItem() {
        playerState.setCurrentQueueId(null);
        assertThatThrownBy(() -> playbackService.restart())
                .isInstanceOf(com.homektv.common.ApiException.class)
                .hasMessageContaining("当前没有正在播放的歌曲");

        playerState.setCurrentQueueId(100L);
        playerState.setPositionMs(1234L);
        playerState.setSeekSequence(6L);
        PlayerState restarted = playbackService.restart();

        assertThat(restarted.getState()).isEqualTo("playing");
        assertThat(restarted.getPositionMs()).isZero();
        assertThat(restarted.getSeekSequence()).isEqualTo(7L);
    }

    @Test
    void recoveryHandlesSkippedCurrentRowsAndAnEmptyPlayerSnapshot() {
        currentQueue.setStatus(QueueService.SKIPPED);
        PlayerState recoveredSkipped = playbackService.recoverAfterRestart();
        assertThat(recoveredSkipped.getCurrentQueueId()).isNull();
        assertThat(recoveredSkipped.getState()).isEqualTo("idle");

        playerState.setCurrentQueueId(null);
        playerState.setState("playing");
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.PLAYING)).thenReturn(List.of());
        PlayerState recoveredEmpty = playbackService.recoverAfterRestart();
        assertThat(recoveredEmpty.getCurrentQueueId()).isNull();
        assertThat(recoveredEmpty.getState()).isEqualTo("idle");
    }

    @Test
    void recoveryKeepsCurrentPlayingRowAndDemotesDuplicatePlayingRows() {
        currentQueue.setStatus(QueueService.PLAYING);
        QueueItem duplicatePlaying = queueItem(201L, QueueService.PLAYING);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.PLAYING))
                .thenReturn(List.of(currentQueue, duplicatePlaying));

        PlayerState recovered = playbackService.recoverAfterRestart();

        assertThat(recovered.getCurrentQueueId()).isEqualTo(100L);
        assertThat(recovered.getState()).isEqualTo("playing");
        assertThat(currentQueue.getStatus()).isEqualTo(QueueService.PLAYING);
        assertThat(duplicatePlaying.getStatus()).isEqualTo(QueueService.WAITING);
        verify(queueRepository).save(duplicatePlaying);
    }

    @Test
    void nextRecordsHistoryOnceWhenTheCurrentPlayingItemIsSkipped() {
        Song song = song(2L);
        when(historyRepository.existsByQueueId(100L)).thenReturn(false);
        when(songRepository.findById(2L)).thenReturn(Optional.of(song));

        PlayerState next = playbackService.next();

        assertThat(currentQueue.getStatus()).isEqualTo(QueueService.SKIPPED);
        assertThat(next.getCurrentQueueId()).isNull();
        assertThat(song.getPlayCount()).isEqualTo(1);
        verify(historyRepository).save(any(PlayHistory.class));
    }

    @Test
    void nextDoesNotDuplicateExistingHistoryOrChangeAQueueRowThatIsNotPlaying() {
        when(historyRepository.existsByQueueId(100L)).thenReturn(true);

        playbackService.next();
        assertThat(currentQueue.getStatus()).isEqualTo(QueueService.SKIPPED);

        playerState.setCurrentQueueId(100L);
        currentQueue.setStatus(QueueService.DONE);

        PlayerState result = playbackService.next();

        assertThat(currentQueue.getStatus()).isEqualTo(QueueService.DONE);
        assertThat(result.getCurrentQueueId()).isNull();
        verify(historyRepository, never()).save(any(PlayHistory.class));
        verify(songRepository, never()).findById(2L);
        verify(historyRepository, times(1)).existsByQueueId(100L);
    }

    @Test
    void offlineClearHandlesWaitingOnlyAndCurrentOnlyContent() {
        playerState.setCurrentQueueId(null);
        QueueItem waiting = queueItem(101L, QueueService.WAITING);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of(waiting));

        assertThat(playbackService.clearOnTvOffline()).isTrue();
        assertThat(waiting.getStatus()).isEqualTo(QueueService.WAITING);
        verify(queueRepository).deleteAll(List.of(waiting));

        playerState.setCurrentQueueId(100L);
        currentQueue.setStatus(QueueService.PLAYING);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of());
        assertThat(playbackService.clearOnTvOffline()).isTrue();
        assertThat(currentQueue.getStatus()).isEqualTo(QueueService.SKIPPED);
    }

    @Test
    void vocalModeAcceptsBothSupportedValues() {
        assertThat(playbackService.setVocalMode("original").getVocalMode()).isEqualTo("original");
        assertThat(playbackService.setVocalMode("accompaniment").getVocalMode()).isEqualTo("accompaniment");
        assertThatThrownBy(() -> playbackService.setVocalMode("stereo"))
                .isInstanceOf(com.homektv.common.ApiException.class);
    }

    @Test
    void vocalSwapRejectsMissingQueueSourceAndSingleTrackStereo() {
        playerState.setCurrentQueueId(null);
        assertThatThrownBy(() -> playbackService.swapVocalTracks())
                .isInstanceOf(com.homektv.common.ApiException.class)
                .hasMessageContaining("当前没有正在播放的歌曲");

        playerState.setCurrentQueueId(100L);
        when(queueRepository.findById(100L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> playbackService.swapVocalTracks())
                .isInstanceOf(com.homektv.common.ApiException.class)
                .hasMessageContaining("当前队列项不存在");

        when(queueRepository.findById(100L)).thenReturn(Optional.of(currentQueue));
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(2L)).thenReturn(List.of());
        assertThatThrownBy(() -> playbackService.swapVocalTracks())
                .isInstanceOf(com.homektv.common.ApiException.class)
                .hasMessageContaining("没有可用文件源");

        SongFile mono = file(204L, 2L);
        mono.setAudioLayout(AudioLayout.NORMAL_STEREO);
        mono.setAudioTracks(1);
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(2L)).thenReturn(List.of(mono));
        assertThatThrownBy(() -> playbackService.swapVocalTracks())
                .isInstanceOf(com.homektv.common.ApiException.class)
                .hasMessageContaining("没有可交换的双音轨");
    }

    @Test
    void vocalSwapMakesLegacyTwoTrackStereoLayoutExplicit() {
        SongFile legacy = file(205L, 2L);
        legacy.setAudioLayout(AudioLayout.NORMAL_STEREO);
        legacy.setAudioTracks(2);
        legacy.setOriginalTrackIndex(0);
        legacy.setAccompanimentTrackIndex(1);
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(2L)).thenReturn(List.of(legacy));

        playbackService.swapVocalTracks();

        assertThat(legacy.getAudioLayout()).isEqualTo(AudioLayout.DUAL_TRACK);
        assertThat(legacy.getAudioLayoutSource()).isEqualTo(AudioLayoutSource.MANUAL);
        verify(fileRepository).save(legacy);
    }

    @Test
    void advancingSkipsNullSongRowsThenStartsThePlayableSong() {
        playerState.setCurrentQueueId(null);
        playerState.setState("idle");
        QueueItem missingSongId = queueItem(101L, QueueService.WAITING);
        missingSongId.setSongId(null);
        QueueItem candidate = queueItem(102L, QueueService.WAITING);
        Song noId = new Song();
        Song playable = song(candidate.getSongId());
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(missingSongId, candidate));
        when(songRepository.findAllById(java.util.Set.of(candidate.getSongId())))
                .thenReturn(Arrays.asList(null, noId, playable));
        when(fileRepository.findSongIdsWithReadyFile(java.util.Set.of(candidate.getSongId())))
                .thenReturn(java.util.Set.of(candidate.getSongId()));

        playbackService.play();

        assertThat(missingSongId.getStatus()).isEqualTo(QueueService.SKIPPED);
        assertThat(candidate.getStatus()).isEqualTo(QueueService.PLAYING);
        assertThat(playerState.getCurrentQueueId()).isEqualTo(candidate.getId());
    }

    @Test
    void seekClampsAgainstKnownSongDuration() {
        Song song = song(2L);
        song.setDurationMs(120_000);
        when(songRepository.findById(2L)).thenReturn(Optional.of(song));

        PlayerState sought = playbackService.seek(999_999L);
        assertThat(sought.getPositionMs()).isEqualTo(119_000L);
    }

    @Test
    void nextAndFinishedTriggerHistoryAutoPlayWithoutWritingHistoryAndAvoidImmediateRepeat() {
        when(historyRepository.findDistinctRecentSongIds(300)).thenReturn(List.of(2L, 3L));
        Song s2 = song(2L);
        Song s3 = song(3L);
        when(songRepository.findAllById(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(s2, s3));
        when(fileRepository.findSongIdsWithReadyFile(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(java.util.Set.of(2L, 3L));
        when(queueRepository.save(any(QueueItem.class))).thenAnswer(invocation -> {
            QueueItem saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(501L);
            return saved;
        });

        PlayerState next = playbackService.next();

        assertThat(next.getState()).isEqualTo("playing");
        assertThat(next.getCurrentQueueId()).isEqualTo(501L);
        org.mockito.ArgumentCaptor<QueueItem> savedCaptor = org.mockito.ArgumentCaptor.forClass(QueueItem.class);
        verify(queueRepository, times(2)).save(savedCaptor.capture());
        QueueItem autoItem = savedCaptor.getAllValues().getLast();
        assertThat(autoItem.isAutoPlay()).isTrue();
        assertThat(autoItem.getSongId()).isEqualTo(3L); // excluded previous songId=2L
        assertThat(autoItem.getStatus()).isEqualTo(QueueService.PLAYING);

        // Now finish the auto-play track: it must NOT record PlayHistory or increment playCount
        autoItem.setStatus(QueueService.PLAYING);
        when(queueRepository.findById(501L)).thenReturn(Optional.of(autoItem));
        int countBefore = s3.getPlayCount();
        org.mockito.Mockito.clearInvocations(historyRepository);

        FinishResult finishedAuto = playbackService.onFinished(501L);
        assertThat(finishedAuto.status()).isEqualTo(FinishResult.Status.APPLIED);
        assertThat(s3.getPlayCount()).isEqualTo(countBefore);
        verify(historyRepository, never()).save(any(PlayHistory.class));
    }

    @Test
    void startIfIdlePreemptsActiveAutoPlayImmediatelyWhenUserOrdersASong() {
        QueueItem autoPlaying = queueItem(400L, QueueService.PLAYING);
        autoPlaying.setSongId(10L);
        autoPlaying.setAutoPlay(true);
        playerState.setCurrentQueueId(400L);
        playerState.setState("playing");
        when(queueRepository.findById(400L)).thenReturn(Optional.of(autoPlaying));

        QueueItem userOrdered = queueItem(401L, QueueService.WAITING);
        userOrdered.setSongId(20L);
        Song s20 = song(20L);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(List.of(userOrdered));
        when(songRepository.findAllById(java.util.Set.of(20L))).thenReturn(List.of(s20));
        when(fileRepository.findSongIdsWithReadyFile(java.util.Set.of(20L))).thenReturn(java.util.Set.of(20L));

        assertThat(playbackService.startIfIdle()).isTrue();
        assertThat(autoPlaying.getStatus()).isEqualTo(QueueService.SKIPPED);
        assertThat(userOrdered.getStatus()).isEqualTo(QueueService.PLAYING);
        assertThat(playerState.getCurrentQueueId()).isEqualTo(401L);
        verify(historyRepository, never()).save(any(PlayHistory.class));
    }

    @Test
    void startAutoPlayIfIdleRespectsPlayerIdleAndHistoryAvailability() {
        playerState.setCurrentQueueId(null);
        playerState.setState("idle");
        when(historyRepository.findDistinctRecentSongIds(300)).thenReturn(List.of());
        assertThat(playbackService.startAutoPlayIfIdle()).isFalse();
        assertThat(playerState.getState()).isEqualTo("idle");

        when(historyRepository.findDistinctRecentSongIds(300)).thenReturn(List.of(9L));
        when(songRepository.findAllById(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(song(9L)));
        when(fileRepository.findSongIdsWithReadyFile(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(java.util.Set.of(9L));
        when(queueRepository.save(any(QueueItem.class))).thenAnswer(invocation -> {
            QueueItem saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(601L);
            return saved;
        });

        assertThat(playbackService.startAutoPlayIfIdle()).isTrue();
        assertThat(playerState.getState()).isEqualTo("playing");
        assertThat(playerState.getCurrentQueueId()).isEqualTo(601L);

        assertThat(playbackService.startAutoPlayIfIdle()).isFalse();
    }

    private static SongFile file(long id, long songId) {
        SongFile file = new SongFile();
        file.setId(id);
        file.setSongId(songId);
        file.setValid(true);
        return file;
    }

    private static Song song(long id) {
        Song song = new Song();
        song.setId(id);
        song.setTitle("测试歌曲" + id);
        song.setArtist("测试歌手");
        song.setStatus("ok");
        return song;
    }

    private static QueueItem queueItem(long id, String status) {
        QueueItem item = new QueueItem();
        item.setId(id);
        item.setSongId(id + 1L);
        item.setStatus(status);
        return item;
    }
}
