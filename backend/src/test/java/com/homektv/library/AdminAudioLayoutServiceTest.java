package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.SongFile;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.AudioLayoutUpdateRequest;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAudioLayoutServiceTest {

    private final SongFileRepository fileRepo = mock(SongFileRepository.class);
    private final AdminService service = new AdminService(
            mock(SongRepository.class), fileRepo, mock(PlayHistoryRepository.class),
            mock(WsBroadcaster.class), mock(AssetWriter.class), mock(QueueItemRepository.class),
            mock(PlayerStateRepository.class), new AppProperties());

    @BeforeEach
    void returnTheUpdatedEntityFromTheRepository() {
        when(fileRepo.save(any(SongFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void updatesDualChannelAndKeepsTheSemanticDefaultLeftRight() {
        SongFile file = file(7L, 1);
        when(fileRepo.findById(7L)).thenReturn(Optional.of(file));

        var result = service.updateAudioLayout(7L, new AudioLayoutUpdateRequest(
                AudioLayout.DUAL_CHANNEL, null, null,
                AudioChannel.RIGHT, AudioChannel.LEFT));

        assertThat(result.layout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
        assertThat(result.originalChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(result.accompanimentChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(file.getOriginalTrackIndex()).isNull();
        assertThat(file.getAccompanimentTrackIndex()).isNull();
        verify(fileRepo).save(file);
    }

    @Test
    void normalStereoClearsTrackAssignmentsAndRestoresDefaultChannels() {
        SongFile file = file(12L, 2);
        file.setAudioLayout(AudioLayout.DUAL_TRACK);
        file.setOriginalTrackIndex(1);
        file.setAccompanimentTrackIndex(0);
        file.setVocalTrackIndex(0);
        file.setVocalConfidence("HIGH");
        when(fileRepo.findById(12L)).thenReturn(Optional.of(file));

        var result = service.updateAudioLayout(12L, new AudioLayoutUpdateRequest(
                AudioLayout.NORMAL_STEREO, null, null, null, null));

        assertThat(result.layout()).isEqualTo(AudioLayout.NORMAL_STEREO);
        assertThat(result.originalChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(result.accompanimentChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(file.getOriginalTrackIndex()).isNull();
        assertThat(file.getAccompanimentTrackIndex()).isNull();
        assertThat(file.getVocalTrackIndex()).isNull();
        assertThat(file.getVocalConfidence()).isNull();
        verify(fileRepo).save(file);
    }

    @Test
    void rejectsDualChannelMappingThatUsesTheSameChannelForBothRoles() {
        SongFile file = file(13L, 2);
        when(fileRepo.findById(13L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.updateAudioLayout(13L, new AudioLayoutUpdateRequest(
                AudioLayout.DUAL_CHANNEL, null, null,
                AudioChannel.LEFT, AudioChannel.LEFT)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不同声道");
        verify(fileRepo, never()).save(any(SongFile.class));
    }

    @Test
    void dualTrackDefaultsReuseStoredIndicesAndThenUseStandardTrackOrder() {
        SongFile stored = file(14L, 3);
        stored.setOriginalTrackIndex(1);
        stored.setAccompanimentTrackIndex(2);
        when(fileRepo.findById(14L)).thenReturn(Optional.of(stored));

        var storedResult = service.updateAudioLayout(14L, new AudioLayoutUpdateRequest(
                AudioLayout.DUAL_TRACK, null, null, null, null));

        assertThat(storedResult.originalTrackIndex()).isEqualTo(1);
        assertThat(storedResult.accompanimentTrackIndex()).isEqualTo(2);
        assertThat(storedResult.originalChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(storedResult.accompanimentChannel()).isEqualTo(AudioChannel.RIGHT);

        SongFile fresh = file(15L, 2);
        when(fileRepo.findById(15L)).thenReturn(Optional.of(fresh));
        var freshResult = service.updateAudioLayout(15L, new AudioLayoutUpdateRequest(
                AudioLayout.DUAL_TRACK, null, null, null, null));

        assertThat(freshResult.originalTrackIndex()).isEqualTo(0);
        assertThat(freshResult.accompanimentTrackIndex()).isEqualTo(1);
    }

    @Test
    void rejectsNegativePastEndAndDuplicateTrackAssignments() {
        SongFile file = file(16L, 2);
        when(fileRepo.findById(16L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.updateAudioLayout(16L, new AudioLayoutUpdateRequest(
                AudioLayout.DUAL_TRACK, -1, 1, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("原唱音轨 index 越界");
        assertThatThrownBy(() -> service.updateAudioLayout(16L, new AudioLayoutUpdateRequest(
                AudioLayout.DUAL_TRACK, 0, 2, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("伴唱音轨 index 越界");
        assertThatThrownBy(() -> service.updateAudioLayout(16L, new AudioLayoutUpdateRequest(
                AudioLayout.DUAL_TRACK, 1, 1, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("同一条音轨");
        verify(fileRepo, never()).save(any(SongFile.class));
    }

    @Test
    void refusesToSwapDualTracksWhenEitherAssignmentIsMissing() {
        SongFile file = file(19L, 2);
        file.setAudioLayout(AudioLayout.DUAL_TRACK);
        file.setOriginalTrackIndex(0);
        when(fileRepo.findById(19L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.swapAudioLayout(19L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("缺少原唱或伴唱音轨");
        verify(fileRepo, never()).save(any(SongFile.class));
    }

    @Test
    void missingFileAndMissingRequestAreReportedWithoutSaving() {
        when(fileRepo.findById(17L)).thenReturn(Optional.empty());
        when(fileRepo.findById(18L)).thenReturn(Optional.of(file(18L, 2)));

        assertThatThrownBy(() -> service.updateAudioLayout(17L, new AudioLayoutUpdateRequest(
                AudioLayout.NORMAL_STEREO, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("文件源不存在");
        assertThatThrownBy(() -> service.updateAudioLayout(18L, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("缺少音频布局设置");
        verify(fileRepo, never()).save(any(SongFile.class));
    }

    @Test
    void swapsChannelsWithoutChangingTheFilePath() {
        SongFile file = file(8L, 1);
        file.setAudioLayout(AudioLayout.DUAL_CHANNEL);
        when(fileRepo.findById(8L)).thenReturn(Optional.of(file));

        var result = service.swapAudioLayout(8L);

        assertThat(result.originalChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(result.accompanimentChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(file.getFilePath()).isEqualTo("/music/test.mkv");
        verify(fileRepo).save(file);
    }

    @Test
    void rejectsTrackAssignmentsThatDoNotExistInTheMedia() {
        SongFile file = file(9L, 1);
        when(fileRepo.findById(9L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.updateAudioLayout(9L, new AudioLayoutUpdateRequest(
                        AudioLayout.DUAL_TRACK, 0, 1,
                        AudioChannel.LEFT, AudioChannel.RIGHT)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("音轨");
    }

    @Test
    void confirmVocalTrackRejectsSingleTrackMedia() {
        SongFile file = file(11L, 1);
        when(fileRepo.findById(11L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.confirmVocalTrack(11L, 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("至少需要 2");
        verify(fileRepo, never()).save(any(SongFile.class));
    }

    @Test
    void updatesAndSwapsDualTrackAssignments() {
        SongFile file = file(10L, 2);
        when(fileRepo.findById(10L)).thenReturn(Optional.of(file));

        var result = service.updateAudioLayout(10L, new AudioLayoutUpdateRequest(
                AudioLayout.DUAL_TRACK, 1, 0,
                AudioChannel.RIGHT, AudioChannel.LEFT));
        assertThat(result.layout()).isEqualTo(AudioLayout.DUAL_TRACK);
        assertThat(result.originalTrackIndex()).isEqualTo(1);
        assertThat(result.accompanimentTrackIndex()).isEqualTo(0);

        result = service.swapAudioLayout(10L);
        assertThat(result.originalTrackIndex()).isEqualTo(0);
        assertThat(result.accompanimentTrackIndex()).isEqualTo(1);
    }

    private static SongFile file(long id, int tracks) {
        SongFile file = new SongFile();
        file.setId(id);
        file.setSongId(1L);
        file.setFilePath("/music/test.mkv");
        file.setFormat("mkv");
        file.setAudioTracks(tracks);
        file.setOriginalChannel(AudioChannel.LEFT);
        file.setAccompanimentChannel(AudioChannel.RIGHT);
        return file;
    }
}
