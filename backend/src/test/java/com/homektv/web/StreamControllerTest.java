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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
