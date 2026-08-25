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
