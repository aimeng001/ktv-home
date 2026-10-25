package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.playback.PlaybackResolutionService;
import com.homektv.repo.SongFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaybackTransportConsistencyTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @MethodSource("automaticTransportCases")
    void normalResolutionDescriptorMatchesTheExistingStreamRoute(
            String extension, String format, String mediaType, String expectedKind, int expectedLaunches)
            throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transport-" + extension + "-" + mediaType));
        byte[] sourceBytes = new byte[]{0x01, 0x02, 0x03};
        Path media = Files.write(library.resolve("song." + extension), sourceBytes);
        SongFile source = new SongFile();
        source.setId(71L);
        source.setSongId(7L);
        source.setFilePath(media.toString());
        source.setFormat(format);
        source.setMediaType(mediaType);
        source.setValid(true);
        source.setProbePending(false);
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(71L)).thenReturn(Optional.of(source));
        AppProperties properties = new AppProperties();
        properties.setKtvLibraryPath(library.toString());
        AtomicInteger processLaunches = new AtomicInteger();
        var process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{'f', 'm', 'p', '4'}));
        when(process.isAlive()).thenReturn(false);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);

        StreamController controller = new StreamController(repository, properties,
                new SongAvailabilityPolicy(repository), builder -> {
                    processLaunches.incrementAndGet();
                    return process;
                });
        var descriptor = new PlaybackResolutionService(repository).resolveReady(source, false);
        var response = controller.stream(71L, null, null);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        response.getBody().writeTo(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(descriptor.kind()).isEqualTo(expectedKind);
        assertThat(descriptor.sourceFileId()).isEqualTo(71L);
        assertThat(descriptor.variantId()).isNull();
        assertThat(processLaunches.get()).isEqualTo(expectedLaunches);
        assertThat(body.toByteArray()).containsExactly(expectedLaunches == 1
                ? new byte[]{'f', 'm', 'p', '4'} : sourceBytes);
        try (var files = Files.list(library)) {
            assertThat(files.toList()).containsExactly(media);
        }
    }

    private static Stream<Arguments> automaticTransportCases() {
        return Stream.of(
                Arguments.of("rm", "unknown", "KTV_VIDEO", "LIVE_TRANSCODE", 1),
                Arguments.of("RMVB", "RMVB", "MV", "LIVE_TRANSCODE", 1),
                Arguments.of("bin", "application/vnd.realmedia", "KTV_VIDEO", "LIVE_TRANSCODE", 1),
                Arguments.of("mp4", "mp4", "KTV_VIDEO", "NATIVE", 0),
                Arguments.of("MKV", "matroska", "MV", "NATIVE", 0),
                Arguments.of("ts", "mpeg-ts", "KTV_VIDEO", "NATIVE", 0),
                Arguments.of("rmvb", "rmvb", "AUDIO", "NATIVE", 0));
    }
}
