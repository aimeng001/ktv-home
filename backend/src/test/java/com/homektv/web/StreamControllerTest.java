package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.library.LibraryMode;
import com.homektv.library.LibraryModePolicy;
import com.homektv.repo.SongFileRepository;
import com.homektv.library.SongAvailabilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
        when(mockProcess.isAlive()).thenReturn(true);

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
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(7);
        when(process.isAlive()).thenReturn(false);
        StreamController controller = new StreamController(repository, props,
                new SongAvailabilityPolicy(repository), builder -> process);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var response = controller.streamInternal(18L, null, null, true);

        assertThatThrownBy(() -> response.getBody().writeTo(output))
                .isInstanceOf(IOException.class);
        assertThat(output.toByteArray()).containsExactly(partialOutput);
        verify(process).waitFor(5, TimeUnit.SECONDS);
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
        when(process.isAlive()).thenReturn(true);
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
        when(process.isAlive()).thenReturn(true);
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
}
