package com.homektv.playback;

import com.homektv.config.AppProperties;
import com.homektv.domain.PlaybackVariant;
import com.homektv.domain.PlaybackVariantProfile;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.repo.PlaybackVariantRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.dto.PlaybackDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlaybackVariantServiceTest {

    private final Executor idleExecutor = runnable -> { };

    @Test
    void rmvbResolvesToPreparingSidecarWithoutWritingSource() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        Path sourcePath = Files.createFile(sourceRoot.resolve("song.rmvb"));
        SongFile source = source(7L, sourcePath, "rmvb");
        SongFileRepository files = mock(SongFileRepository.class);
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        when(files.findById(7L)).thenReturn(Optional.of(source));
        when(variants.findBySourceFileIdAndSourceFingerprintAndProfile(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(variants.saveAndFlush(any(PlaybackVariant.class))).thenAnswer(invocation -> {
            PlaybackVariant variant = invocation.getArgument(0);
            variant.setId(99L);
            return variant;
        });
        AppProperties props = props(sourceRoot, dataRoot);

        PlaybackVariantService service = new PlaybackVariantService(files, variants, mock(FFprobeService.class),
                props, "ffmpeg", idleExecutor, command -> { throw new AssertionError("job must be asynchronous"); },
                Duration.ofSeconds(5));

        PlaybackDescriptor descriptor = service.resolve(7L, false);

        assertThat(descriptor.kind()).isEqualTo("TRANSCODE");
        assertThat(descriptor.status()).isEqualTo("PREPARING");
        assertThat(descriptor.variantId()).isEqualTo(99L);
        assertThat(Files.size(sourcePath)).isZero();
        assertThat(Files.list(dataRoot).toList()).isEmpty();
    }

    @Test
    void ordinaryMp4ResolvesToNativeStream() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        Path sourcePath = Files.createFile(sourceRoot.resolve("song.mp4"));
        SongFile source = source(8L, sourcePath, "mp4");
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findById(8L)).thenReturn(Optional.of(source));

        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        PlaybackVariantService service = new PlaybackVariantService(files, variants,
                mock(FFprobeService.class), props(sourceRoot, dataRoot), "ffmpeg", idleExecutor,
                command -> { throw new AssertionError("native source must not transcode"); }, Duration.ofSeconds(5));

        PlaybackDescriptor descriptor = service.resolve(8L, false);

        assertThat(descriptor.kind()).isEqualTo("NATIVE");
        assertThat(descriptor.status()).isEqualTo("READY");
        assertThat(descriptor.streamUrl()).isEqualTo("/api/stream/8");
        verifyNoInteractions(variants);
    }

    @Test
    void failedVariantIsReportedWithoutImplicitlyResubmittingWhenForced() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        Path sourcePath = Files.createFile(sourceRoot.resolve("song.rmvb"));
        SongFile source = source(9L, sourcePath, "rmvb");
        SongFileRepository files = mock(SongFileRepository.class);
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        when(files.findById(9L)).thenReturn(Optional.of(source));
        PlaybackVariant failed = new PlaybackVariant(9L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);
        failed.setId(199L);
        failed.markFailed("TRANSCODE_FAILED", "ffmpeg exit=1");
        when(variants.findBySourceFileIdAndSourceFingerprintAndProfile(any(), any(), any()))
                .thenReturn(Optional.of(failed));

        PlaybackVariantService service = new PlaybackVariantService(files, variants, mock(FFprobeService.class),
                props(sourceRoot, dataRoot), "ffmpeg", idleExecutor,
                command -> { throw new AssertionError("failed variant must not implicitly restart"); },
                Duration.ofSeconds(5));

        PlaybackDescriptor descriptor = service.resolve(9L, true);

        assertThat(descriptor.status()).isEqualTo("FAILED");
        assertThat(descriptor.errorCode()).isEqualTo("TRANSCODE_FAILED");
        verify(variants, never()).saveAndFlush(any(PlaybackVariant.class));
    }
    private static SongFile source(Long id, Path path, String format) {
        SongFile source = new SongFile();
        source.setId(id);
        source.setFilePath(path.toString());
        source.setFormat(format);
        source.setMediaType("MV");
        source.setProbePending(false);
        source.setValid(true);
        source.setAudioTracks(1);
        source.setFileRole("EXTERNAL_READ_ONLY");
        return source;
    }

    private static AppProperties props(Path sourceRoot, Path dataRoot) {
        AppProperties props = new AppProperties();
        props.setSourceLibraryPath(sourceRoot.toString());
        props.setDataPath(dataRoot.toString());
        props.setLibraryMode(com.homektv.library.LibraryMode.EXTERNAL_READ_ONLY);
        return props;
    }

}
