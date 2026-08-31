package com.homektv.library;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AssetWriterCacheSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsReadableCacheWhoseParentDirectoryResolvesOutsideDataRoot() throws Exception {
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.write(outside.resolve("avatar.jpg"), new byte[]{1, 2, 3});
        createDirectoryLinkOrSkip(data.resolve("artist-covers"), outside);

        AppProperties props = new AppProperties();
        props.setDataPath(data.toString());
        props.setKtvLibraryPath(tempDir.resolve("managed").toString());
        props.setSourceLibraryPath(tempDir.resolve("source").toString());
        props.setLibraryMode(LibraryMode.MANAGED);

        assertThat(new AssetWriter(props).isReadableCache("artist-covers/avatar.jpg")).isFalse();
    }

    private static void createDirectoryLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | SecurityException | IOException ignored) {
            // Windows often permits a junction even when symbolic links need elevation.
        }
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        Assumptions.assumeTrue(process.waitFor() == 0 && Files.isDirectory(link),
                "当前运行权限不允许创建目录 Junction");
    }
}
