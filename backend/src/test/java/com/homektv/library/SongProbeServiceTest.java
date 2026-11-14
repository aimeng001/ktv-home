package com.homektv.library;

import com.homektv.domain.AudioLayout;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.MediaProbe;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SongProbeServiceTest {

    @Mock
    private SongRepository songRepo;

    @Mock
    private SongFileRepository fileRepo;

    @Mock
    private MediaProbeStage mediaProbeStage;

    @Mock
    private SongAvailabilityPolicy availabilityPolicy;

    private SongProbeService probeService;

    @BeforeEach
    void setUp() {
        probeService = new SongProbeService(songRepo, fileRepo, mediaProbeStage, availabilityPolicy);
    }

    @Test
    void alreadyPlayableSongSkipsProbe() {
        Song song = new Song();
        song.setId(100L);
        song.setStatus("ok");

        when(songRepo.findById(100L)).thenReturn(Optional.of(song));
        when(availabilityPolicy.isPlayable(song)).thenReturn(true);

        probeService.ensurePlayable(100L);

        verify(mediaProbeStage, never()).probe(any());
        verify(mediaProbeStage, never()).probeRequired(any());
        verify(fileRepo, never()).save(any());
    }

    @Test
    void unreadySongTriggersProbeAndUpdatesEntities(@TempDir Path tempDir) throws IOException {
        Path mockFile = tempDir.resolve("test_song.mp4");
        Files.writeString(mockFile, "fake-video-content");

        Song song = new Song();
        song.setId(200L);
        song.setStatus("ok");
        song.setMediaType("pending_probe");

        SongFile sf = new SongFile();
        sf.setId(2001L);
        sf.setSongId(200L);
        sf.setFilePath(mockFile.toString());
        sf.setFormat("MP4");
        sf.setProbePending(true);
        sf.setValid(true);
        sf.setFileMtime(OffsetDateTime.now());

        when(songRepo.findById(200L)).thenReturn(Optional.of(song));
        when(availabilityPolicy.isPlayable(song)).thenReturn(false);
        when(fileRepo.findBySongIdOrderByPriorityDesc(200L)).thenReturn(List.of(sf));

        MediaProbe mockProbe = new MediaProbe(
                180_000L,
                2,
                0,
                true,
                "1920x1080",
                List.of()
        );
        when(mediaProbeStage.probe(mockFile)).thenReturn(new MediaProbeStage.Result(mockProbe, null, true));

        probeService.ensurePlayable(200L);

        verify(mediaProbeStage).probe(mockFile);
        verify(fileRepo).save(sf);
        verify(songRepo).save(song);

        assertThat(sf.isProbePending()).isFalse();
        assertThat(sf.getMediaType()).isEqualTo(MediaClassifier.KTV_VIDEO);
        assertThat(sf.getAudioLayout()).isEqualTo(AudioLayout.DUAL_TRACK);
        assertThat(song.getDurationMs()).isEqualTo(180_000);
        assertThat(song.getMediaType()).isEqualTo(MediaClassifier.KTV_VIDEO);
        assertThat(song.isHasVocalTrack()).isTrue();
    }

    @Test
    void failedProbeThrowsApiException(@TempDir Path tempDir) throws IOException {
        Path mockFile = tempDir.resolve("corrupt_song.mp4");
        Files.writeString(mockFile, "corrupted");

        Song song = new Song();
        song.setId(300L);
        song.setStatus("ok");

        SongFile sf = new SongFile();
        sf.setId(3001L);
        sf.setSongId(300L);
        sf.setFilePath(mockFile.toString());
        sf.setProbePending(true);

        when(songRepo.findById(300L)).thenReturn(Optional.of(song));
        when(availabilityPolicy.isPlayable(song)).thenReturn(false);
        when(fileRepo.findBySongIdOrderByPriorityDesc(300L)).thenReturn(List.of(sf));

        when(mediaProbeStage.probe(mockFile)).thenReturn(
                new MediaProbeStage.Result(null, new RuntimeException("ffprobe parse error"), true)
        );

        assertThatThrownBy(() -> probeService.ensurePlayable(300L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SONG_PROBE_FAILED");
    }
}
