package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.library.LibraryMode;
import com.homektv.library.LibraryModePolicy;
import com.homektv.repo.SongFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
            SongFileRepository repository = mock(SongFileRepository.class);
            when(repository.findById(1L)).thenReturn(Optional.of(songFile));

            var response = new StreamController(repository).stream(1L, "bytes=0-");
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
}
