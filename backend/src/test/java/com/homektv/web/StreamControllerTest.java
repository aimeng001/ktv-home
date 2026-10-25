package com.homektv.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homektv.config.AppProperties;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.SongFile;
import com.homektv.library.LibraryMode;
import com.homektv.library.LibraryModePolicy;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.SongFileRepository;
import com.homektv.resource.ResourceBudget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class StreamControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void openEndedRangeStreamsTheWholeRemainingFile() throws Exception {
        Path media = Files.createTempFile("home-ktv-stream-", ".mpg");
        try {
            byte[] content = new byte[2 * 1024 * 1024 + 17];
            for (int i = 0; i < content.length; i++) {
                content[i] = (byte) (i % 251);
            }
            Files.write(media, content);

            SongFile songFile = new SongFile();
            songFile.setFilePath(media.toString());
            songFile.setFormat("mpg");
            songFile.setMediaType("KTV_VIDEO");
            SongFileRepository repository = mock(SongFileRepository.class);
            when(repository.findById(1L)).thenReturn(Optional.of(songFile));
            AppProperties props = new AppProperties();
            props.setKtvLibraryPath(media.getParent().toString());

            var response = new StreamController(repository, props).stream(1L, "bytes=0-");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            StreamingResponseBody body = response.getBody();
            assertThat(body).isNotNull();
            body.writeTo(output);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
            assertThat(response.getHeaders().getFirst("Content-Range"))
                    .isEqualTo("bytes 0-" + (content.length - 1) + "/" + content.length);
            assertThat(response.getHeaders().getContentLength()).isEqualTo(content.length);
            assertThat(output.toByteArray()).containsExactly(content);
        } finally {
            Files.deleteIfExists(media);
        }
    }

    @Test
    void closedRangeStreamsOnlyTheRequestedBytes() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("closed-range-library"));
        byte[] content = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Path media = Files.write(library.resolve("song.mp4"), content);
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("mp4");
        songFile.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(20L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());

        ResponseEntity<StreamingResponseBody> response = new StreamController(repository, props)
                .stream(20L, "bytes=3-5");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes 3-5/10");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
        assertThat(output.toByteArray()).containsExactly((byte) 3, (byte) 4, (byte) 5);
    }

    @Test
    void staticStreamsAreBoundedAndReleaseTheirAdmissionAfterSuccessOrClientAbort() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("bounded-static-stream-library"));
        Path media = Files.write(library.resolve("song.mp4"), new byte[]{1, 2, 3, 4});
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("mp4");
        songFile.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(42L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        ResourceBudget budget = new ResourceBudget(4, 4, 4, 1, 2, () -> false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), "ffmpeg", null, budget);

        var first = controller.streamInternal(42L, null, null, false);
        var second = controller.streamInternal(42L, "bytes=1-2", null, false);
        var rejected = controller.streamInternal(42L, null, null, false);
        var invalidRange = controller.streamInternal(42L, "bytes=9-", null, false);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(rejected.getBody()).isNull();
        assertThat(invalidRange.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(budget.activeMediaStreamCount()).isEqualTo(2);

        first.getBody().writeTo(new ByteArrayOutputStream());
        assertThat(budget.activeMediaStreamCount()).isEqualTo(1);
        second.getBody().writeTo(new ByteArrayOutputStream());
        assertThat(budget.activeMediaStreamCount()).isZero();

        var aborted = controller.streamInternal(42L, null, null, false);
        assertThatThrownBy(() -> aborted.getBody().writeTo(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("simulated client disconnect");
            }
        })).isInstanceOf(IOException.class);
        assertThat(budget.activeMediaStreamCount()).isZero();
    }

    @Test
    void malformedAndUnsatisfiableRangesReturn416WithTheResourceLength() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("rejected-range-library"));
        Path media = Files.write(library.resolve("song.mp4"), new byte[]{1, 2, 3, 4});
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("mp4");
        songFile.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(21L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        StreamController controller = new StreamController(repository, props);

        ResponseEntity<StreamingResponseBody> malformed = controller.stream(21L, "items=0-1");
        ResponseEntity<StreamingResponseBody> beyondEnd = controller.stream(21L, "bytes=4-");
        ResponseEntity<StreamingResponseBody> reversed = controller.stream(21L, "bytes=3-1");
        ResponseEntity<StreamingResponseBody> malformedNegativeStart = controller.stream(21L, "bytes=-1-2");

        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(malformed.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */4");
        assertThat(malformed.getBody()).isNull();
        assertThat(beyondEnd.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(beyondEnd.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */4");
        assertThat(beyondEnd.getBody()).isNull();
        assertThat(reversed.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(reversed.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */4");
        assertThat(reversed.getBody()).isNull();
        assertThat(malformedNegativeStart.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(malformedNegativeStart.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */4");
        assertThat(malformedNegativeStart.getBody()).isNull();
    }

    @Test
    void blankRangeStreamsWholeFileAndEmptyFileReturnsAnEmptyBody() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("blank-range-library"));
        Path media = Files.write(library.resolve("song.mp4"), new byte[]{4, 5, 6});
        Path emptyMedia = Files.createFile(library.resolve("empty.mp4"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("mp4");
        songFile.setMediaType("KTV_VIDEO");
        SongFile emptySongFile = new SongFile();
        emptySongFile.setFilePath(emptyMedia.toString());
        emptySongFile.setFormat("mp4");
        emptySongFile.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(22L)).thenReturn(Optional.of(songFile));
        when(repository.findById(23L)).thenReturn(Optional.of(emptySongFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        StreamController controller = new StreamController(repository, props);

        ResponseEntity<StreamingResponseBody> blankRange = controller.stream(22L, "  ");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        blankRange.getBody().writeTo(output);
        ResponseEntity<StreamingResponseBody> emptyFile = controller.stream(23L, null);
        ByteArrayOutputStream emptyOutput = new ByteArrayOutputStream();
        emptyFile.getBody().writeTo(emptyOutput);

        assertThat(blankRange.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(blankRange.getHeaders().getContentLength()).isEqualTo(3);
        assertThat(output.toByteArray()).containsExactly((byte) 4, (byte) 5, (byte) 6);
        assertThat(emptyFile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(emptyFile.getHeaders().getContentLength()).isZero();
        assertThat(emptyOutput.toByteArray()).isEmpty();
    }

    @Test
    void readyRowsWithNullOrBlankPathsReturnNotFoundBeforeStartingFfmpeg() throws Exception {
        SongFileRepository repository = mock(SongFileRepository.class);
        Map<Long, SongFile> files = new HashMap<>();
        when(repository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(files.get(invocation.getArgument(0))));
        AtomicBoolean launcherCalled = new AtomicBoolean(false);
        StreamController controller = new StreamController(repository, new AppProperties(),
                new SongAvailabilityPolicy(repository), builder -> {
                    launcherCalled.set(true);
                    throw new AssertionError("a row without a usable path must not launch FFmpeg");
                });

        for (long id : new long[]{30L, 31L}) {
            SongFile file = new SongFile();
            file.setMediaType("KTV_VIDEO");
            file.setFilePath(id == 30L ? null : " ");
            files.put(id, file);

            ResponseEntity<StreamingResponseBody> response = controller.stream(id, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNull();
        }

        assertThat(launcherCalled).isFalse();
    }

    @Test
    void missingOrNonRegularPathReturnsNotFoundWithoutStartingFfmpeg() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("missing-media-library"));
        Path directory = Files.createDirectory(library.resolve("folder.mkv"));
        SongFileRepository repository = mock(SongFileRepository.class);
        Map<Long, SongFile> files = new HashMap<>();
        when(repository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(files.get(invocation.getArgument(0))));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicBoolean launcherCalled = new AtomicBoolean(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    launcherCalled.set(true);
                    throw new AssertionError("unreadable paths must not launch FFmpeg");
                });
        Path missing = library.resolve("missing.mkv");
        for (var entry : List.of(Map.entry(32L, missing), Map.entry(33L, directory))) {
            SongFile file = new SongFile();
            file.setFilePath(entry.getValue().toString());
            file.setFormat("mkv");
            file.setMediaType("KTV_VIDEO");
            files.put(entry.getKey(), file);

            ResponseEntity<StreamingResponseBody> response = controller.stream(entry.getKey(), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNull();
        }

        assertThat(launcherCalled).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "directory"})
    void pathBecomingUnavailableAfterSafetyValidationReturnsNotFound(String postValidationState) throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("path-change-library-" + postValidationState));
        Path media = Files.writeString(library.resolve("song.rmvb"), "synthetic media");
        Path validatedMedia = media.toRealPath();
        SongFile file = new SongFile();
        file.setFilePath(media.toString());
        file.setFormat("rmvb");
        file.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(45L)).thenReturn(Optional.of(file));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicBoolean launcherCalled = new AtomicBoolean();
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    launcherCalled.set(true);
                    throw new AssertionError("a path changed after validation must not launch FFmpeg");
                });
        AtomicBoolean replacedAfterValidation = new AtomicBoolean();
        ResponseEntity<StreamingResponseBody> response;

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.isRegularFile(validatedMedia, LinkOption.NOFOLLOW_LINKS)).thenAnswer(invocation -> {
                boolean wasRegular = (boolean) invocation.callRealMethod();
                if (wasRegular && replacedAfterValidation.compareAndSet(false, true)) {
                    Files.delete(media);
                    if (postValidationState.equals("directory")) Files.createDirectory(media);
                }
                return wasRegular;
            });

            response = controller.stream(45L, null);
        }

        assertThat(replacedAfterValidation).isTrue();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        assertThat(launcherCalled).isFalse();
        if (postValidationState.equals("directory")) {
            assertThat(Files.isDirectory(media)).isTrue();
        } else {
            assertThat(media).doesNotExist();
        }
    }

    @Test
    void staticStreamStopsAtEofWhenTheSourceShrinksAfterHeadersArePrepared() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("shrinking-media-library"));
        Path media = Files.write(library.resolve("shrinking.mp4"), new byte[]{1, 2, 3, 4});
        SongFile file = new SongFile();
        file.setFilePath(media.toString());
        file.setFormat("mp4");
        file.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(34L)).thenReturn(Optional.of(file));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        StreamController controller = new StreamController(repository, props);

        ResponseEntity<StreamingResponseBody> response = controller.stream(34L, null);
        Files.write(media, new byte[0]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        response.getBody().writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(4);
        assertThat(output.toByteArray()).isEmpty();
    }

    @Test
    void pendingProbeFileCannotBeStreamed() throws Exception {
        Path media = Files.createTempFile("home-ktv-pending-", ".mkv");
        try {
            Files.write(media, new byte[]{0x01, 0x02});
            SongFile songFile = new SongFile();
            songFile.setFilePath(media.toString());
            songFile.setFormat("mkv");
            songFile.setMediaType("PENDING_PROBE");
            songFile.setProbePending(true);
            SongFileRepository repository = mock(SongFileRepository.class);
            when(repository.findById(4L)).thenReturn(Optional.of(songFile));

            var response = new StreamController(repository).stream(4L, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNull();
        } finally {
            Files.deleteIfExists(media);
        }
    }

    @Test
    void missingFileRecordReturnsNotFoundWithoutLaunchingTranscode() {
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(24L)).thenReturn(Optional.empty());
        AtomicBoolean launcherCalled = new AtomicBoolean(false);
        StreamController controller = new StreamController(repository, new AppProperties(),
                new SongAvailabilityPolicy(repository), builder -> {
                    launcherCalled.set(true);
                    throw new AssertionError("a missing file record must not launch FFmpeg");
                });

        ResponseEntity<StreamingResponseBody> response = controller.stream(24L, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        assertThat(launcherCalled).isFalse();
    }

    @Test
    void managedModeDoesNotStreamAReadyFileOutsideTheKtvLibraryRoot() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path outside = tempDir.resolve("outside.mkv");
        Files.write(outside, new byte[]{0x01, 0x02});

        SongFile songFile = new SongFile();
        songFile.setFilePath(outside.toString());
        songFile.setFormat("mkv");
        songFile.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(5L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());

        var response = new StreamController(repository, props).stream(5L, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void externalModeDoesNotStreamMediaSymlinkOutsideSourceRoot() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("nas-copy"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.write(outside.resolve("outside.mkv"), new byte[]{0x01, 0x02});
        Path linkDir = source.resolve("linked-directory");
        createDirectoryLinkOrSkip(linkDir, outside);
        Path link = linkDir.resolve("outside.mkv");

        SongFile songFile = new SongFile();
        songFile.setFilePath(link.toString());
        songFile.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        SongFileRepository repository = org.mockito.Mockito.mock(SongFileRepository.class);
        when(repository.findById(2L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        props.setSourceLibraryPath(source.toString());

        var response = new StreamController(repository, props).stream(2L, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void externalModeDoesNotStreamLexicalPathOutsideSourceRoot() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("nas-copy"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path outsideMedia = outside.resolve("outside.mkv");
        Files.write(outsideMedia, new byte[]{0x01, 0x02});

        SongFile songFile = new SongFile();
        songFile.setFilePath(source.resolve("..").resolve("outside").resolve("outside.mkv").toString());
        songFile.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        SongFileRepository repository = org.mockito.Mockito.mock(SongFileRepository.class);
        when(repository.findById(3L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        props.setSourceLibraryPath(source.toString());

        var response = new StreamController(repository, props).stream(3L, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(Files.readAllBytes(outsideMedia)).containsExactly((byte) 0x01, (byte) 0x02);
    }

    private static void createDirectoryLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | SecurityException e) {
        } catch (java.io.IOException e) {
        }
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            Assumptions.assumeTrue(false, "当前文件系统不支持目录链接");
        }
        String command = "mklink /J \"" + link + "\" \"" + target + "\"";
        Process process = new ProcessBuilder("cmd.exe", "/c", command)
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        Assumptions.assumeTrue(exitCode == 0 && Files.isDirectory(link),
                "当前运行权限不允许创建目录 Junction");
    }

    @Test
    void rmvbStreamServesOnTheFlyTranscodeMp4WithoutWritingDisk() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("rmvb-lib"));
        Path rmvbFile = Files.createFile(library.resolve("song.rmvb"));
        Files.write(rmvbFile, new byte[]{0x2E, 0x52, 0x4D, 0x46}); // .RMF

        SongFile songFile = new SongFile();
        songFile.setFilePath(rmvbFile.toString());
        songFile.setFormat("rmvb");
        songFile.setMediaType("KTV_VIDEO");
        songFile.setAudioTracks(2);
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(10L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());

        AtomicReference<List<String>> executedCommand = new AtomicReference<>();
        byte[] fakeFmp4Data = new byte[]{0x00, 0x00, 0x00, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(fakeFmp4Data));
        when(mockProcess.isAlive()).thenReturn(false);
        when(mockProcess.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(mockProcess.exitValue()).thenReturn(0);

        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository),
                builder -> {
                    executedCommand.set(builder.command());
                    return mockProcess;
                });

        var response = controller.stream(10L, null, 15.5);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("video/mp4");
        assertThat(response.getHeaders().getFirst("Accept-Ranges")).isNull();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(fakeFmp4Data);

        // 验证命令行参数：快速定位、保留双音轨、锁成立体声独立双声道、管道输出
        List<String> cmd = executedCommand.get();
        assertThat(cmd).isNotNull();
        assertThat(cmd).containsSequence("-ss", "15.500");
        assertThat(cmd).containsSequence("-map", "0:v:0");
        assertThat(cmd).containsSequence("-map", "0:a:0");
        assertThat(cmd).containsSequence("-map", "0:a:1");
        assertThat(cmd).containsSequence("-c:a", "aac");
        assertThat(cmd).containsSequence("-ac", "2");
        assertThat(cmd).contains("pipe:1");

        // 验证磁盘零缓存写入
        assertThat(Files.list(library).toList()).containsExactly(rmvbFile);
    }

    @Test
    void legacyFilenameStillSelectsMemoryTranscodeWhenFormatMetadataIsUnknown() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("legacy-extension-library"));
        Path media = Files.write(library.resolve("song.rmvb"), new byte[]{0x2E, 0x52, 0x4D, 0x46});
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("unknown");
        songFile.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(25L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicReference<List<String>> executedCommand = new AtomicReference<>();
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{'f', 'm', 'p', '4'}));
        when(process.isAlive()).thenReturn(false);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    executedCommand.set(builder.command());
                    return process;
                });

        ResponseEntity<StreamingResponseBody> response = controller.stream(25L, null, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("video/mp4");
        assertThat(output.toByteArray()).containsExactly((byte) 'f', (byte) 'm', (byte) 'p', (byte) '4');
        assertThat(executedCommand.get()).contains("pipe:1");
        try (var files = Files.list(library)) {
            assertThat(files.toList()).containsExactly(media);
        }
    }

    @ParameterizedTest(name = "{0} with {1} audio track(s) keeps stereo output in memory")
    @MethodSource("transcodeAudioLayouts")
    void transcodePreservesAudioLayoutAndStreamsWithoutDiskCache(
            AudioLayout audioLayout, int audioTracks) throws Exception {
        Path library = Files.createDirectory(tempDir.resolve(audioTracks + "-track-library"));
        Path media = Files.write(library.resolve("song.rmvb"), new byte[]{0x2E, 0x52, 0x4D, 0x46});
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("rmvb");
        songFile.setMediaType("KTV_VIDEO");
        songFile.setAudioLayout(audioLayout);
        songFile.setAudioTracks(audioTracks);

        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(11L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicReference<List<String>> executedCommand = new AtomicReference<>();
        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{'f', 'm', 'p', '4'}));
        when(mockProcess.isAlive()).thenReturn(false);
        when(mockProcess.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(mockProcess.exitValue()).thenReturn(0);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    executedCommand.set(builder.command());
                    return mockProcess;
                });

        ResponseEntity<StreamingResponseBody> response = controller.stream(11L, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertThat(output.toByteArray()).containsExactly((byte) 'f', (byte) 'm', (byte) 'p', (byte) '4');
        List<String> command = executedCommand.get();
        if (audioTracks > 1) {
            for (int audioIndex = 0; audioIndex < audioTracks; audioIndex++) {
                assertThat(command).containsSequence("-map", "0:a:" + audioIndex);
            }
            assertThat(command).doesNotContain("0:a?");
        } else {
            assertThat(command).containsSequence("-map", "0:a?");
        }
        assertThat(command).containsSequence("-ac", "2").contains("pipe:1");
        assertThat(Files.list(library).toList()).containsExactly(media);
    }

    private static Stream<Arguments> transcodeAudioLayouts() {
        return Stream.of(
                Arguments.of(AudioLayout.DUAL_TRACK, 4),
                Arguments.of(AudioLayout.DUAL_CHANNEL, 1));
    }

    @Test
    void zeroSecondSeekStartsAtTheBeginningWithoutAddingFfmpegSeekArguments() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("zero-seek-library"));
        Path media = Files.write(library.resolve("song.rmvb"), new byte[]{0x2E, 0x52, 0x4D, 0x46});
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("rmvb");
        songFile.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(12L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicReference<List<String>> executedCommand = new AtomicReference<>();
        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(mockProcess.isAlive()).thenReturn(false);
        when(mockProcess.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(mockProcess.exitValue()).thenReturn(0);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    executedCommand.set(builder.command());
                    return mockProcess;
                });

        var response = controller.stream(12L, null, 0.0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        response.getBody().writeTo(new ByteArrayOutputStream());
        assertThat(executedCommand.get()).doesNotContain("-ss").contains("pipe:1");
        assertThat(Files.list(library).toList()).containsExactly(media);
    }

    @Test
    void forceTranscodeQueryUsesPipeForNonLegacyVideoContainer() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("forced-mkv-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        Files.write(media, new byte[]{0x01, 0x02, 0x03});

        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        songFile.setAudioTracks(1);
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(13L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());

        AtomicReference<List<String>> executedCommand = new AtomicReference<>();
        byte[] fakeFmp4Data = new byte[]{0x00, 0x00, 0x00, 0x20, 'f', 't', 'y', 'p'};
        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(fakeFmp4Data));
        when(mockProcess.isAlive()).thenReturn(false);
        when(mockProcess.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(mockProcess.exitValue()).thenReturn(0);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    executedCommand.set(builder.command());
                    return mockProcess;
                });
        MockMvc mvc = standaloneSetup(controller).build();

        MvcResult result = mvc.perform(get("/api/stream/13").param("transcode", "true")).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }

        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(result.getResponse().getContentType()).startsWith("video/mp4");
        assertThat(result.getResponse().getContentAsByteArray()).containsExactly(fakeFmp4Data);
        assertThat(executedCommand.get()).containsSequence("-c:v", "libx264");
        assertThat(executedCommand.get()).containsSequence("-c:a", "aac");
        assertThat(executedCommand.get()).contains("pipe:1");
        assertThat(Files.list(library).toList()).containsExactly(media);
    }

    @Test
    void forceTranscodeRejectsUnsupportedMediaAndInvalidSeekBeforeStartingFfmpeg() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("invalid-force-lib"));
        Path video = Files.createFile(library.resolve("song.mkv"));
        Path audio = Files.createFile(library.resolve("song.mp3"));
        SongFile videoFile = new SongFile();
        videoFile.setFilePath(video.toString());
        videoFile.setFormat("matroska");
        videoFile.setMediaType("MV");
        SongFile audioFile = new SongFile();
        audioFile.setFilePath(audio.toString());
        audioFile.setFormat("mp3");
        audioFile.setMediaType("AUDIO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(14L)).thenReturn(Optional.of(videoFile));
        when(repository.findById(15L)).thenReturn(Optional.of(audioFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicBoolean launcherCalled = new AtomicBoolean(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    launcherCalled.set(true);
                    throw new AssertionError("Invalid transcode requests must not start FFmpeg");
                });
        MockMvc mvc = standaloneSetup(controller).build();

        var unsupported = mvc.perform(get("/api/stream/15").param("transcode", "true")).andReturn();
        var negativeStart = mvc.perform(get("/api/stream/14").param("transcode", "true")
                .param("start", "-1")).andReturn();
        var excessiveStart = mvc.perform(get("/api/stream/14").param("transcode", "true")
                .param("start", "86401")).andReturn();
        var notANumberStart = mvc.perform(get("/api/stream/14").param("transcode", "true")
                .param("start", "NaN")).andReturn();
        var infiniteStart = mvc.perform(get("/api/stream/14").param("transcode", "true")
                .param("start", "Infinity")).andReturn();

        assertThat(unsupported.getResponse().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(negativeStart.getResponse().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(excessiveStart.getResponse().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(notANumberStart.getResponse().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(infiniteStart.getResponse().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(launcherCalled.get()).isFalse();
    }

    @Test
    void forceTranscodeAdmissionReturns429BeforeStartingFifthProcess() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-limit-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(16L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicInteger launcherCalls = new AtomicInteger();
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.isAlive()).thenReturn(false);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    launcherCalls.incrementAndGet();
                    return process;
                });

        List<ResponseEntity<StreamingResponseBody>> responses = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(controller.streamInternal(16L, null, null, true));
        }

        assertThat(responses).hasSize(5);
        assertThat(responses.get(4).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(launcherCalls.get()).isZero();
        for (int i = 0; i < 4; i++) {
            responses.get(i).getBody().writeTo(new ByteArrayOutputStream());
        }
        assertThat(launcherCalls.get()).isEqualTo(4);
        assertThat(controller.streamInternal(16L, null, null, true).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void liveTranscodeAndStaticStreamsShareTheConfiguredMediaResponseLimit() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("shared-media-stream-limit-library"));
        Path staticMedia = Files.write(library.resolve("song.mp4"), new byte[]{1, 2});
        Path legacyMedia = Files.createFile(library.resolve("song.rmvb"));
        SongFile staticSong = new SongFile();
        staticSong.setFilePath(staticMedia.toString());
        staticSong.setFormat("mp4");
        staticSong.setMediaType("KTV_VIDEO");
        SongFile legacySong = new SongFile();
        legacySong.setFilePath(legacyMedia.toString());
        legacySong.setFormat("rmvb");
        legacySong.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(43L)).thenReturn(Optional.of(staticSong));
        when(repository.findById(44L)).thenReturn(Optional.of(legacySong));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        ResourceBudget budget = new ResourceBudget(4, 4, 4, 1, 1, () -> false);
        AtomicInteger launcherCalls = new AtomicInteger();
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.isAlive()).thenReturn(false);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), "ffmpeg", builder -> {
                    launcherCalls.incrementAndGet();
                    return process;
                }, budget);

        var staticResponse = controller.streamInternal(43L, null, null, false);
        var rejectedTranscode = controller.streamInternal(44L, null, null, true);

        assertThat(staticResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejectedTranscode.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(budget.activeLiveTranscodeCount()).isZero();
        assertThat(launcherCalls.get()).isZero();

        staticResponse.getBody().writeTo(new ByteArrayOutputStream());
        var admittedTranscode = controller.streamInternal(44L, null, null, true);
        assertThat(admittedTranscode.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(budget.activeMediaStreamCount()).isEqualTo(1);
        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);
        admittedTranscode.getBody().writeTo(new ByteArrayOutputStream());

        assertThat(launcherCalls.get()).isEqualTo(1);
        assertThat(budget.activeMediaStreamCount()).isZero();
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void clientAbortKillsFfmpegProcess() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("abort-lib"));
        Path rmvbFile = Files.createFile(library.resolve("song.rmvb"));

        SongFile songFile = new SongFile();
        songFile.setFilePath(rmvbFile.toString());
        songFile.setFormat("rmvb");
        songFile.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(11L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());

        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[1024]));
        diesWhenDestroyed(mockProcess);

        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository),
                builder -> mockProcess);

        var response = controller.stream(11L, null, null);
        assertThat(response.getBody()).isNotNull();

        // 模拟客户端断开连接抛出 IOException
        OutputStream brokenOut = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Client disconnected");
            }
        };

        response.getBody().writeTo(brokenOut);

        // 验证强杀进程被触发
        verify(mockProcess).destroyForcibly();
        assertThat(controller.streamInternal(11L, null, null, true).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void clientAbortWaitsForRealProcessExitAndReturnsAdmissionSlot() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("real-abort-lib"));
        Path media = Files.createFile(library.resolve("song.rmvb"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("rmvb");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(22L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicReference<Process> launched = new AtomicReference<>();
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    List<String> command = System.getProperty("os.name", "")
                            .toLowerCase(java.util.Locale.ROOT).contains("win")
                            ? List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                                    "[Console]::Out.Write('x'); [Console]::Out.Flush(); Start-Sleep -Seconds 30")
                            : List.of("sh", "-c", "printf x; exec sleep 30");
                    Process process = new ProcessBuilder(command).start();
                    launched.set(process);
                    return process;
                });

        var response = controller.streamInternal(22L, null, null, true);
        OutputStream brokenOut = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("synthetic client disconnect");
            }
        };
        response.getBody().writeTo(brokenOut);

        Process process = launched.get();
        assertThat(process).isNotNull();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.isAlive()).isFalse();
        assertThat(controller.streamInternal(22L, null, null, true).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void nonZeroFfmpegExitAfterPartialOutputFailsTheStream() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-nonzero-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(18L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        Process process = mock(Process.class);
        byte[] partialOutput = new byte[]{0x00, 0x00, 0x00, 0x10, 'f', 't', 'y', 'p'};
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(partialOutput));
        String diagnosticPrefix = "discard-this-prefix-";
        String diagnosticMarker = "final-ffmpeg-diagnostic-marker";
        String stderr = diagnosticPrefix + "x".repeat(70 * 1024)
                + diagnosticMarker + " api_key=hidden-secret-token";
        when(process.getErrorStream()).thenReturn(
                new ByteArrayInputStream(stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(7);
        when(process.isAlive()).thenReturn(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> process);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var response = controller.streamInternal(18L, null, null, true);

        Logger logger = (Logger) LoggerFactory.getLogger(LiveTranscodeSession.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ERROR);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> response.getBody().writeTo(output))
                    .isInstanceOf(IOException.class);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        assertThat(output.toByteArray()).containsExactly(partialOutput);
        verify(process).waitFor(5, TimeUnit.SECONDS);
        assertThat(appender.list).anySatisfy(event -> {
            String message = event.getFormattedMessage();
            assertThat(message).contains(diagnosticMarker).doesNotContain(diagnosticPrefix);
            assertThat(message).doesNotContain("hidden-secret-token");
            String tail = message.substring(message.indexOf("stderrTail=") + "stderrTail=".length());
            assertThat(tail.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(64 * 1024);
        });
    }

    @Test
    void stderrDrainStartsBeforeStdoutIsCopied() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-stderr-order-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(19L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());

        CountDownLatch stderrReadStarted = new CountDownLatch(1);
        byte[] mediaOutput = new byte[]{0x00, 0x00, 0x00, 0x10, 'f', 't', 'y', 'p'};
        Process process = mock(Process.class);
        when(process.getErrorStream()).thenReturn(new InputStream() {
            @Override
            public int read() {
                stderrReadStarted.countDown();
                return -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                stderrReadStarted.countDown();
                return -1;
            }
        });
        when(process.getInputStream()).thenReturn(new InputStream() {
            private boolean emitted;

            @Override
            public int read() throws IOException {
                if (emitted) return -1;
                awaitStderrRead(stderrReadStarted);
                emitted = true;
                return mediaOutput[0] & 0xff;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (emitted) return -1;
                awaitStderrRead(stderrReadStarted);
                emitted = true;
                System.arraycopy(mediaOutput, 0, buffer, offset, mediaOutput.length);
                return mediaOutput.length;
            }
        });
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        when(process.isAlive()).thenReturn(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> process);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        controller.streamInternal(19L, null, null, true).getBody().writeTo(output);

        assertThat(output.toByteArray()).containsExactly(mediaOutput);
    }

    @Test
    void largeChildStderrDoesNotBlockTheMediaPipe() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-stderr-flood-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(23L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicReference<Process> launched = new AtomicReference<>();
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    builder.command(stderrFloodCommand());
                    Process process = builder.start();
                    launched.set(process);
                    return process;
                });

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThatThrownBy(() -> controller.streamInternal(23L, null, null, true)
                            .getBody().writeTo(output)).isInstanceOf(IOException.class));
        } finally {
            Process process = launched.get();
            if (process != null && process.isAlive()) process.destroyForcibly();
        }

        assertThat(output.toByteArray()).containsExactly("ftyp".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<String> stderrFloodCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::Error.Write(('x' * 262144)); [Console]::Out.Write('ftyp'); exit 7");
        }
        return List.of("sh", "-c",
                "head -c 262144 /dev/zero | tr '\\000' x >&2; printf ftyp; exit 7");
    }

    private static void awaitStderrRead(CountDownLatch stderrReadStarted) throws IOException {
        try {
            if (!stderrReadStarted.await(2, TimeUnit.SECONDS)) {
                throw new IOException("stderr drain did not start before stdout copy");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for stderr drain", interrupted);
        }
    }

    @Test
    void ffmpegStdoutReadFailureIsReportedAndReleasesAdmissionSlot() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-stdout-failure-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(20L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("synthetic stdout failure");
            }
        });
        when(process.isAlive()).thenReturn(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> process);

        var response = controller.streamInternal(20L, null, null, true);

        assertThatThrownBy(() -> response.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        assertThat(controller.streamInternal(20L, null, null, true).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void ffmpegExitWaitTimeoutFailsTheStreamAndKillsTheProcess() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-timeout-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(21L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(false);
        diesWhenDestroyed(process);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> process);

        var response = controller.streamInternal(21L, null, null, true);

        assertThatThrownBy(() -> response.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        verify(process).destroyForcibly();
        assertThat(controller.streamInternal(21L, null, null, true).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void unobservableTranscodeExitRetainsAdmissionSlot() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-unobservable-exit-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(23L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        ResourceBudget budget = new ResourceBudget(1, 1, 1, () -> false);
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(false, false);
        when(process.isAlive()).thenReturn(true, true, true);
        when(process.onExit()).thenReturn(null);
        AtomicInteger launchAttempts = new AtomicInteger();
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), "ffmpeg", builder -> {
                    launchAttempts.incrementAndGet();
                    return process;
                }, budget);

        var response = controller.streamInternal(23L, null, null, true);
        assertThatThrownBy(() -> response.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);

        assertThat(budget.snapshot().activeLiveTranscodes()).isEqualTo(1);
        assertThat(controller.streamInternal(23L, null, null, true).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(launchAttempts.get()).isEqualTo(1);
    }

    @Test
    void confirmedTranscodeExitReleasesAdmissionSlot() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-confirmed-exit-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(24L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        ResourceBudget budget = new ResourceBudget(1, 1, 1, () -> false);
        Process unconfirmed = mock(Process.class);
        CompletableFuture<Process> exit = new CompletableFuture<>();
        when(unconfirmed.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(unconfirmed.waitFor(5, TimeUnit.SECONDS)).thenReturn(false, false);
        when(unconfirmed.isAlive()).thenReturn(true, true, true);
        when(unconfirmed.onExit()).thenReturn(exit);
        Process replacement = mock(Process.class);
        when(replacement.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(replacement.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(replacement.exitValue()).thenReturn(0);
        when(replacement.isAlive()).thenReturn(false);
        AtomicInteger launchAttempts = new AtomicInteger();
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), "ffmpeg", builder ->
                launchAttempts.incrementAndGet() == 1 ? unconfirmed : replacement, budget);

        var failedResponse = controller.streamInternal(24L, null, null, true);
        assertThatThrownBy(() -> failedResponse.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        assertThat(controller.streamInternal(24L, null, null, true).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(budget.snapshot().activeLiveTranscodes()).isEqualTo(1);

        exit.complete(unconfirmed);

        assertThat(budget.snapshot().activeLiveTranscodes()).isZero();
        var recoveredResponse = controller.streamInternal(24L, null, null, true);
        assertThat(recoveredResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        recoveredResponse.getBody().writeTo(new ByteArrayOutputStream());
        assertThat(launchAttempts.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedExitObserverUsesProcessLivenessBeforeReleasingAdmissionSlot(boolean processStillAlive)
            throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-exit-observer-" + processStillAlive));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(25L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        ResourceBudget budget = new ResourceBudget(1, 1, 1, () -> false);
        Process unconfirmed = mock(Process.class);
        CompletableFuture<Process> exit = new CompletableFuture<>();
        when(unconfirmed.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(unconfirmed.waitFor(5, TimeUnit.SECONDS)).thenReturn(false, false);
        when(unconfirmed.isAlive()).thenReturn(true, true, true, processStillAlive);
        when(unconfirmed.onExit()).thenReturn(exit);
        Process replacement = mock(Process.class);
        when(replacement.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(replacement.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(replacement.exitValue()).thenReturn(0);
        when(replacement.isAlive()).thenReturn(false);
        AtomicInteger launchAttempts = new AtomicInteger();
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), "ffmpeg", builder ->
                launchAttempts.incrementAndGet() == 1 ? unconfirmed : replacement, budget);

        var failedResponse = controller.streamInternal(25L, null, null, true);
        assertThatThrownBy(() -> failedResponse.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        assertThat(controller.streamInternal(25L, null, null, true).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        exit.completeExceptionally(new IllegalStateException("synthetic process exit observer failure"));

        assertThat(budget.snapshot().activeLiveTranscodes()).isEqualTo(processStillAlive ? 1 : 0);
        if (processStillAlive) {
            assertThat(controller.streamInternal(25L, null, null, true).getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(launchAttempts.get()).isEqualTo(1);
        } else {
            var recoveredResponse = controller.streamInternal(25L, null, null, true);
            assertThat(recoveredResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            recoveredResponse.getBody().writeTo(new ByteArrayOutputStream());
            assertThat(launchAttempts.get()).isEqualTo(2);
        }
    }

    @Test
    void interruptedFfmpegWaitRestoresInterruptAndReleasesAdmissionSlot() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-interrupted-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(19L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("test interruption"));
        diesWhenDestroyed(process);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> process);

        var response = controller.streamInternal(19L, null, null, true);
        try {
            assertThatThrownBy(() -> response.getBody().writeTo(new ByteArrayOutputStream()))
                    .isInstanceOf(IOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(process).destroyForcibly();
        assertThat(controller.streamInternal(19L, null, null, true).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void ffmpegLaunchFailureReleasesTranscodeAdmissionSlot() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-launch-failure-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(17L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicInteger launchAttempts = new AtomicInteger();
        Process successfulProcess = mock(Process.class);
        when(successfulProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(successfulProcess.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(successfulProcess.exitValue()).thenReturn(0);
        when(successfulProcess.isAlive()).thenReturn(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    if (launchAttempts.incrementAndGet() == 1) {
                        throw new IOException("synthetic process launch failure");
                    }
                    return successfulProcess;
                });

        var failedBodyResponse = controller.streamInternal(17L, null, null, true);
        assertThatThrownBy(() -> failedBodyResponse.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        var nextResponse = controller.streamInternal(17L, null, null, true);

        assertThat(nextResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        nextResponse.getBody().writeTo(new ByteArrayOutputStream());
        assertThat(launchAttempts.get()).isEqualTo(2);
    }

    @Test
    void nullFfmpegLaunchResultFailsAndReleasesAdmissionSlot() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("transcode-null-launch-lib"));
        Path media = Files.createFile(library.resolve("song.mkv"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("matroska");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(17L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicInteger launchAttempts = new AtomicInteger();
        Process successfulProcess = mock(Process.class);
        when(successfulProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(successfulProcess.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(successfulProcess.exitValue()).thenReturn(0);
        when(successfulProcess.isAlive()).thenReturn(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder ->
                launchAttempts.incrementAndGet() == 1 ? null : successfulProcess);

        var failedBodyResponse = controller.streamInternal(17L, null, null, true);
        assertThatThrownBy(() -> failedBodyResponse.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        var nextResponse = controller.streamInternal(17L, null, null, true);

        assertThat(nextResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        nextResponse.getBody().writeTo(new ByteArrayOutputStream());
        assertThat(launchAttempts.get()).isEqualTo(2);
    }

    @Test
    void standardMp4DoesNotTriggerTranscodeProcess() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("mp4-lib"));
        Path mp4File = Files.createFile(library.resolve("song.mp4"));
        Files.write(mp4File, new byte[]{0x01, 0x02, 0x03});

        SongFile songFile = new SongFile();
        songFile.setFilePath(mp4File.toString());
        songFile.setFormat("mp4");
        songFile.setMediaType("KTV_VIDEO");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(12L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());

        AtomicBoolean launcherCalled = new AtomicBoolean(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository),
                builder -> {
                    launcherCalled.set(true);
                    throw new AssertionError("Standard MP4 must not trigger transcode process");
                });

        var response = controller.stream(12L, null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("video/mp4");
        assertThat(launcherCalled.get()).isFalse();
    }

    @Test
    void commonMediaFormatsUseRangeFastPathWithTheirExpectedContentTypes() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("fast-path-formats"));
        List<String[]> formats = List.of(
                new String[]{".mkv", "matroska", "video/x-matroska"},
                new String[]{".mkv", "unknown", "video/x-matroska"},
                new String[]{".mp4", "mp4", "video/mp4"},
                new String[]{".m4v", null, "video/mp4"},
                new String[]{".MP4", null, "video/mp4"},
                new String[]{".webm", null, "video/webm"},
                new String[]{".avi", null, "video/x-msvideo"},
                new String[]{".mpg", "mpeg", "video/mpeg"},
                new String[]{".mpeg", null, "video/mpeg"},
                new String[]{".flac", null, "audio/flac"},
                new String[]{".mp3", null, "audio/mpeg"},
                new String[]{".m4a", null, "audio/mp4"},
                new String[]{".aac", null, "audio/mp4"},
                new String[]{".wav", null, "audio/wav"},
                new String[]{".ogg", null, "audio/ogg"},
                new String[]{".bin", null, "application/octet-stream"});
        SongFileRepository repository = mock(SongFileRepository.class);
        Map<Long, SongFile> filesById = new HashMap<>();
        when(repository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(filesById.get(invocation.getArgument(0))));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());
        AtomicBoolean launcherCalled = new AtomicBoolean(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> {
                    launcherCalled.set(true);
                    throw new AssertionError("supported common formats must stay on the Range path");
                });

        for (int index = 0; index < formats.size(); index++) {
            String[] format = formats.get(index);
            Path media = Files.write(library.resolve("sample-" + index + format[0]),
                    new byte[]{(byte) index, 0x2A});
            SongFile songFile = new SongFile();
            songFile.setFilePath(media.toString());
            songFile.setFormat(format[1]);
            songFile.setMediaType(format[2].startsWith("video/") ? "KTV_VIDEO" : "AUDIO");
            long fileId = 100L + index;
            filesById.put(fileId, songFile);

            var response = controller.stream(fileId, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType().toString()).isEqualTo(format[2]);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            response.getBody().writeTo(output);
            assertThat(output.toByteArray()).containsExactly((byte) index, (byte) 0x2A);
        }

        assertThat(launcherCalled).isFalse();
        try (var libraryFiles = Files.list(library)) {
            assertThat(libraryFiles.toList()).hasSize(formats.size());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "configured-ffmpeg"})
    void liveTranscodeUsesConfiguredExecutableOrFallsBackWhenItIsBlank(String configuredExecutable)
            throws Exception {
        Path library = Files.createDirectory(tempDir.resolve(
                "ffmpeg-executable-" + (configuredExecutable == null ? "null" :
                        configuredExecutable.isBlank() ? "blank" : "configured")));
        Path media = Files.createFile(library.resolve("song.mp4"));
        SongFile songFile = new SongFile();
        songFile.setFilePath(media.toString());
        songFile.setFormat("mp4");
        songFile.setMediaType("MV");
        SongFileRepository repository = mock(SongFileRepository.class);
        when(repository.findById(300L)).thenReturn(Optional.of(songFile));
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(library.toString());

        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        when(process.isAlive()).thenReturn(false);
        AtomicReference<String> launchedExecutable = new AtomicReference<>();
        ResourceBudget budget = new ResourceBudget(4, 4, 4, () -> false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), configuredExecutable, builder -> {
                    launchedExecutable.set(builder.command().get(0));
                    return process;
                }, budget);

        var response = controller.streamInternal(300L, null, null, true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        response.getBody().writeTo(new ByteArrayOutputStream());

        String expectedExecutable = configuredExecutable == null || configuredExecutable.isBlank()
                ? "ffmpeg" : configuredExecutable;
        assertThat(launchedExecutable.get()).isEqualTo(expectedExecutable);
        assertThat(budget.activeMediaStreamCount()).isZero();
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    private static void diesWhenDestroyed(Process process) {
        AtomicBoolean alive = new AtomicBoolean(true);
        when(process.isAlive()).thenAnswer(invocation -> alive.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            alive.set(false);
            return process;
        }).when(process).destroyForcibly();
    }
}
