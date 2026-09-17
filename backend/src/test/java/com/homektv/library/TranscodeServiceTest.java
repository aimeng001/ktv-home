package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.testutil.FakeFfmpegProcess;
import com.homektv.repo.SongFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranscodeServiceTest {

    @TempDir
    Path temp;

    @Test
    void usesConfiguredFfmpegPathForStandaloneSongTranscode() throws Exception {
        Path sourcePath = temp.resolve("草蜢-爱.mp4");
        Files.writeString(sourcePath, "source-media");
        SongFile source = new SongFile();
        source.setId(10L);
        source.setSongId(42L);
        source.setFilePath(sourcePath.toString());
        source.setFormat("mp4");
        source.setPriority(1);

        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findBySongIdOrderByPriorityDesc(42L)).thenReturn(List.of(source));
        when(files.findByFilePath(sourcePath.resolveSibling("草蜢-爱.mkv").toString()))
                .thenReturn(Optional.empty());
        when(files.save(any(SongFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AtomicReference<List<String>> command = new AtomicReference<>();
        TranscodeService service = new TranscodeService(files, managedProperties(),
                "configured-ffmpeg", ffmpegCommand -> {
                    command.set(ffmpegCommand);
                    return FakeFfmpegProcess.coverFallback(ffmpegCommand, sourcePath);
                });

        TranscodeService.Result result = service.transcodeSong(42L);

        assertThat(Path.of(result.outputPath())).isEqualTo(sourcePath.resolveSibling("草蜢-爱.mkv").toRealPath());
        assertThat(command).hasValueSatisfying(args -> assertThat(args.getFirst()).isEqualTo("configured-ffmpeg"));
    }

    /**
     * 转码派生行必须带着「待探测」状态落库，否则它会立刻被判为可播放行，
     * 并在 FFprobe 完成前被下发给客户端（客户端随后 /api/stream 404）。
     *
     * A transcoded derivative must enter the probe queue before it can be published
     * as a playable source.
     */
    @Test
    void transcodedDerivativeIsPersistedAsPendingProbe() throws Exception {
        Path sourcePath = temp.resolve("草蜢-爱.mp4");
        Files.writeString(sourcePath, "source-media");
        SongFile source = new SongFile();
        source.setId(10L);
        source.setSongId(42L);
        source.setFilePath(sourcePath.toString());
        source.setFormat("mp4");
        source.setPriority(1);

        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findBySongIdOrderByPriorityDesc(42L)).thenReturn(List.of(source));
        when(files.findByFilePath(sourcePath.resolveSibling("草蜢-爱.mkv").toString()))
                .thenReturn(Optional.empty());
        AtomicReference<SongFile> saved = new AtomicReference<>();
        when(files.save(any(SongFile.class))).thenAnswer(invocation -> {
            SongFile entity = invocation.getArgument(0);
            saved.set(entity);
            return entity;
        });

        TranscodeService service = new TranscodeService(files, managedProperties(),
                "configured-ffmpeg", ffmpegCommand -> FakeFfmpegProcess.coverFallback(ffmpegCommand, sourcePath));

        service.transcodeSong(42L);

        assertThat(saved.get()).isNotNull();
        assertThat(saved.get().getMediaType()).isEqualTo(MediaClassifier.PENDING_PROBE);
        assertThat(saved.get().isProbePending()).isTrue();
        assertThat(new SongAvailabilityPolicy(files).isReadyFile(saved.get())).isFalse();
    }

    @Test
    void rejectsSuccessfulFfmpegProcessThatProducesAnEmptyOutput() throws Exception {
        Path sourcePath = temp.resolve("source.mp4");
        Path emptyOutputFixture = temp.resolve("empty-output.mkv");
        Files.writeString(sourcePath, "source-media");
        Files.write(emptyOutputFixture, new byte[0]);
        SongFile source = new SongFile();
        source.setId(10L);
        source.setSongId(42L);
        source.setFilePath(sourcePath.toString());
        source.setFormat("mp4");
        source.setPriority(1);

        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findBySongIdOrderByPriorityDesc(42L)).thenReturn(List.of(source));
        when(files.findByFilePath(sourcePath.resolveSibling("source.mkv").toString()))
                .thenReturn(Optional.empty());
        TranscodeService service = new TranscodeService(files, managedProperties(),
                "configured-ffmpeg",
                command -> FakeFfmpegProcess.coverFallback(command, emptyOutputFixture));

        assertThatThrownBy(() -> service.transcodeSong(42L))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("转码失败");
        verify(files, never()).save(any(SongFile.class));
        assertThat(sourcePath.resolveSibling("source.mkv")).doesNotExist();
    }

    @Test
    void managedModeRejectsAReadableSourceOutsideTheKtvLibraryRoot() throws Exception {
        Path sourcePath = temp.resolve("outside-source.mp4");
        Files.writeString(sourcePath, "source-media");
        Path library = Files.createDirectories(temp.resolve("music"));
        SongFile source = new SongFile();
        source.setId(10L);
        source.setSongId(42L);
        source.setFilePath(sourcePath.toString());
        source.setFormat("mp4");
        source.setPriority(1);

        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findBySongIdOrderByPriorityDesc(42L)).thenReturn(List.of(source));
        when(files.findByFilePath(sourcePath.resolveSibling("outside-source.mkv").toString()))
                .thenReturn(Optional.empty());
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        TranscodeService service = new TranscodeService(files, props,
                "configured-ffmpeg", command -> FakeFfmpegProcess.coverFallback(command, sourcePath));

        assertThatThrownBy(() -> service.transcodeSong(42L))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("曲库");
        verify(files, never()).save(any(SongFile.class));
    }

    private AppProperties managedProperties() {
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(temp.toString());
        return props;
    }
}
