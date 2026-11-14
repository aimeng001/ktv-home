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

    @Test
    void singleTrackNormalStereoProbeSetsHasVocalTrackFalse(@TempDir Path tempDir) throws IOException {
        Path mockFile = tempDir.resolve("normal_mv.mp4");
        Files.writeString(mockFile, "dummy");

        Song song = new Song();
        song.setId(401L);
        song.setStatus("ok");
        song.setMediaType(MediaClassifier.PENDING_PROBE);

        SongFile sf = new SongFile();
        sf.setId(4011L);
        sf.setSongId(401L);
        sf.setFilePath(mockFile.toString());
        sf.setAudioLayout(com.homektv.domain.AudioLayout.NORMAL_STEREO);
        sf.setProbePending(true);

        when(songRepo.findById(401L)).thenReturn(Optional.of(song));
        when(availabilityPolicy.isPlayable(song)).thenReturn(false);
        when(fileRepo.findBySongIdOrderByPriorityDesc(401L)).thenReturn(List.of(sf));
        when(mediaProbeStage.probe(mockFile)).thenReturn(
                new MediaProbeStage.Result(new MediaProbe(180_000L, 1, 0, true, "1920x1080"), null, true)
        );

        probeService.ensurePlayable(401L);

        org.assertj.core.api.Assertions.assertThat(sf.getMediaType()).isEqualTo(MediaClassifier.MV);
        org.assertj.core.api.Assertions.assertThat(song.getMediaType()).isEqualTo(MediaClassifier.MV);
        org.assertj.core.api.Assertions.assertThat(song.isHasVocalTrack()).isFalse();
    }

    @Test
    void dualChannelSingleTrackProbeRetainsKtvVideoAndHasVocalTrack(@TempDir Path tempDir) throws IOException {
        Path mockFile = tempDir.resolve("dual_channel_ktv.mkv");
        Files.writeString(mockFile, "dummy");

        Song song = new Song();
        song.setId(402L);
        song.setStatus("ok");
        song.setMediaType(MediaClassifier.PENDING_PROBE);

        SongFile sf = new SongFile();
        sf.setId(4021L);
        sf.setSongId(402L);
        sf.setFilePath(mockFile.toString());
        sf.setAudioLayout(com.homektv.domain.AudioLayout.DUAL_CHANNEL);
        sf.setProbePending(true);

        when(songRepo.findById(402L)).thenReturn(Optional.of(song));
        when(availabilityPolicy.isPlayable(song)).thenReturn(false);
        when(fileRepo.findBySongIdOrderByPriorityDesc(402L)).thenReturn(List.of(sf));
        when(mediaProbeStage.probe(mockFile)).thenReturn(
                new MediaProbeStage.Result(new MediaProbe(200_000L, 1, 0, true, "1920x1080"), null, true)
        );

        probeService.ensurePlayable(402L);

        org.assertj.core.api.Assertions.assertThat(sf.getMediaType()).isEqualTo(MediaClassifier.KTV_VIDEO);
        org.assertj.core.api.Assertions.assertThat(song.getMediaType()).isEqualTo(MediaClassifier.KTV_VIDEO);
        org.assertj.core.api.Assertions.assertThat(song.isHasVocalTrack()).isTrue();
    }

    @Test
    void concurrentOrderAndAdminProbeSerializePerSongEvenAfterFailure(@TempDir Path tempDir) throws Exception {
        Path mockFile = tempDir.resolve("race_song.mkv");
        Files.writeString(mockFile, "dummy");

        Song song = new Song();
        song.setId(403L);
        song.setStatus("ok");
        song.setMediaType(MediaClassifier.PENDING_PROBE);

        SongFile sf = new SongFile();
        sf.setId(4031L);
        sf.setSongId(403L);
        sf.setFilePath(mockFile.toString());
        sf.setAudioLayout(com.homektv.domain.AudioLayout.DUAL_TRACK);
        sf.setProbePending(true);

        when(songRepo.findById(403L)).thenReturn(Optional.of(song));
        when(availabilityPolicy.isPlayable(song)).thenReturn(false);
        when(fileRepo.findBySongIdOrderByPriorityDesc(403L)).thenReturn(List.of(sf));

        java.util.concurrent.CountDownLatch firstEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch secondEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseSecond = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger callSeq = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();

        when(mediaProbeStage.probe(mockFile)).thenAnswer(inv -> {
            int seq = callSeq.incrementAndGet();
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                if (seq == 1) {
                    firstEntered.countDown();
                    releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return new MediaProbeStage.Result(null, new RuntimeException("transient failure"), true);
                }
                if (seq == 2) {
                    secondEntered.countDown();
                    releaseSecond.await(5, java.util.concurrent.TimeUnit.SECONDS);
                }
                return new MediaProbeStage.Result(new MediaProbe(210_000L, 2, 0, true, "1920x1080"), null, true);
            } finally {
                inFlight.decrementAndGet();
            }
        });

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            java.util.concurrent.Future<?> f1 = pool.submit(() -> probeService.ensurePlayable(403L));
            org.assertj.core.api.Assertions.assertThat(firstEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            java.util.concurrent.Future<?> f2 = pool.submit(() -> probeService.ensurePlayable(403L));
            Thread.sleep(60);
            releaseFirst.countDown();

            org.assertj.core.api.Assertions.assertThat(secondEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<Boolean> f3 = pool.submit(() -> probeService.probeAndPersist(403L));
            Thread.sleep(60);
            org.assertj.core.api.Assertions.assertThat(inFlight.get()).isEqualTo(1);

            releaseSecond.countDown();

            assertThatThrownBy(f1::get).hasCauseInstanceOf(ApiException.class);
            f2.get(5, java.util.concurrent.TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThat(f3.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThat(maxInFlight.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void probePersistUpdatesOnlyProbeFieldsWithoutClobberingMetadataEdits(@TempDir Path tempDir) throws IOException {
        Path mockFile = tempDir.resolve("edited_song.mkv");
        Files.writeString(mockFile, "dummy");

        Song staleSong = new Song();
        staleSong.setId(404L);
        staleSong.setStatus("ok");
        staleSong.setTitle("旧歌名");
        staleSong.setArtist("旧歌手");
        staleSong.setMediaType(MediaClassifier.PENDING_PROBE);

        Song latestSong = new Song();
        latestSong.setId(404L);
        latestSong.setStatus("ok");
        latestSong.setTitle("管理员新歌名");
        latestSong.setArtist("管理员新歌手");
        latestSong.setMediaType(MediaClassifier.PENDING_PROBE);

        SongFile sf = new SongFile();
        sf.setId(4041L);
        sf.setSongId(404L);
        sf.setFilePath(mockFile.toString());
        sf.setAudioLayout(com.homektv.domain.AudioLayout.DUAL_TRACK);
        sf.setProbePending(true);

        when(songRepo.findById(404L)).thenReturn(Optional.of(staleSong), Optional.of(latestSong));
        when(fileRepo.findBySongIdOrderByPriorityDesc(404L)).thenReturn(List.of(sf));
        when(fileRepo.findById(4041L)).thenReturn(Optional.of(sf));
        when(mediaProbeStage.probe(mockFile)).thenReturn(
                new MediaProbeStage.Result(new MediaProbe(215_000L, 2, 0, true, "1920x1080"), null, true)
        );

        boolean updated = probeService.probeAndPersist(404L);

        org.assertj.core.api.Assertions.assertThat(updated).isTrue();
        org.mockito.Mockito.verify(songRepo).save(latestSong);
        org.mockito.Mockito.verify(songRepo, org.mockito.Mockito.never()).save(staleSong);
        org.assertj.core.api.Assertions.assertThat(latestSong.getTitle()).isEqualTo("管理员新歌名");
        org.assertj.core.api.Assertions.assertThat(latestSong.getArtist()).isEqualTo("管理员新歌手");
        org.assertj.core.api.Assertions.assertThat(latestSong.getDurationMs()).isEqualTo(215_000);
        org.assertj.core.api.Assertions.assertThat(latestSong.getMediaType()).isEqualTo(MediaClassifier.KTV_VIDEO);
        org.assertj.core.api.Assertions.assertThat(latestSong.isHasVocalTrack()).isTrue();
    }
}
