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
        TranscodeService service = new TranscodeService(files, new AppProperties(),
                "configured-ffmpeg", ffmpegCommand -> {
                    command.set(ffmpegCommand);
                    return FakeFfmpegProcess.coverFallback(ffmpegCommand, sourcePath);
                });

        TranscodeService.Result result = service.transcodeSong(42L);

        assertThat(result.outputPath()).isEqualTo(sourcePath.resolveSibling("草蜢-爱.mkv").toString());
        assertThat(command).hasValueSatisfying(args -> assertThat(args.getFirst()).isEqualTo("configured-ffmpeg"));
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
        TranscodeService service = new TranscodeService(files, new AppProperties(),
                "configured-ffmpeg",
                command -> FakeFfmpegProcess.coverFallback(command, emptyOutputFixture));

        assertThatThrownBy(() -> service.transcodeSong(42L))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("转码失败");
        verify(files, never()).save(any(SongFile.class));
        assertThat(sourcePath.resolveSibling("source.mkv")).doesNotExist();
    }
}
