package com.homektv.library;

import com.homektv.domain.AudioLayout;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SongProjectionServiceTest {

    private final SongRepository songRepository = mock(SongRepository.class);
    private final SongFileRepository fileRepository = mock(SongFileRepository.class);
    private final SongProjectionService service =
            new SongProjectionService(songRepository, fileRepository);

    @Test
    void highestPriorityValidFileDeterminesTheSongProjection() {
        Song song = song(1L, MediaClassifier.MV, false);
        SongFile low = file("/music/low.mkv", 10, MediaClassifier.MV, 1, AudioLayout.NORMAL_STEREO);
        SongFile high = file("/music/high.mkv", 100, MediaClassifier.KTV_VIDEO, 2, AudioLayout.DUAL_TRACK);
        when(songRepository.findById(1L)).thenReturn(Optional.of(song));
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(1L))
                .thenReturn(List.of(low, high));

        assertThat(service.recompute(1L)).isTrue();

        assertThat(song.getMediaType()).isEqualTo(MediaClassifier.KTV_VIDEO);
        assertThat(song.isHasVocalTrack()).isTrue();
        verify(songRepository).save(song);
    }

    @Test
    void equalPriorityUsesStablePathOrdering() {
        Song song = song(2L, MediaClassifier.MV, false);
        SongFile laterPath = file("/music/z.mkv", 10, MediaClassifier.MV, 1, AudioLayout.NORMAL_STEREO);
        SongFile earlierPath = file("/music/a.mkv", 10, MediaClassifier.KTV_VIDEO, 1, AudioLayout.DUAL_CHANNEL);
        when(songRepository.findById(2L)).thenReturn(Optional.of(song));
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(2L))
                .thenReturn(List.of(laterPath, earlierPath));

        service.recompute(2L);

        assertThat(song.getMediaType()).isEqualTo(MediaClassifier.KTV_VIDEO);
        assertThat(song.isHasVocalTrack()).isTrue();
    }

    @Test
    void oneTrackDualChannelFileProjectsAsKtvWithVocalSwitch() {
        Song song = song(3L, MediaClassifier.MV, false);
        SongFile file = file("/music/channel.mkv", 10, MediaClassifier.MV, 1, AudioLayout.DUAL_CHANNEL);
        when(songRepository.findById(3L)).thenReturn(Optional.of(song));
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(3L))
                .thenReturn(List.of(file));

        service.recompute(3L);

        assertThat(song.getMediaType()).isEqualTo(MediaClassifier.KTV_VIDEO);
        assertThat(song.isHasVocalTrack()).isTrue();
    }

    @Test
    void noValidProbedFileDoesNotWriteAStaleProjection() {
        Song song = song(4L, MediaClassifier.KTV_VIDEO, true);
        when(songRepository.findById(4L)).thenReturn(Optional.of(song));
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(4L))
                .thenReturn(List.of());

        assertThat(service.recompute(4L)).isFalse();

        verify(songRepository, never()).save(any(Song.class));
    }

    private static Song song(long id, String mediaType, boolean hasVocal) {
        Song song = new Song();
        song.setId(id);
        song.setTitle("测试");
        song.setArtist("歌手");
        song.setMediaType(mediaType);
        song.setHasVocalTrack(hasVocal);
        song.setFingerprint("fp-" + id);
        return song;
    }

    private static SongFile file(String path, int priority, String mediaType,
                                 int tracks, AudioLayout layout) {
        SongFile file = new SongFile();
        file.setFilePath(path);
        file.setPriority(priority);
        file.setMediaType(mediaType);
        file.setAudioTracks(tracks);
        file.setAudioLayout(layout);
        file.setProbePending(false);
        file.setValid(true);
        return file;
    }
}
