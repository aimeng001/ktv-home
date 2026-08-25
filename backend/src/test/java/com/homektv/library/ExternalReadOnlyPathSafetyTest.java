package com.homektv.library;

import com.homektv.ai.SecretCryptoService;
import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalReadOnlyPathSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void assetCacheSymlinkIntoExternalSourceIsRejected() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("nas-copy"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        createDirectoryLinkOrSkip(data.resolve("lyrics"), source);

        AppProperties props = externalProperties(source, data);

        assertThatThrownBy(() -> new AssetWriter(props).writeLyric("fingerprint", "歌词"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("EXTERNAL_READ_ONLY");
        assertThat(source.resolve("fingerprint.lrc")).doesNotExist();
    }

    @Test
    void secretKeySymlinkIntoExternalSourceIsRejected() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("nas-copy"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        createDirectoryLinkOrSkip(data.resolve("secrets"), source);

        AppProperties props = externalProperties(source, data);
        props.setConfigMasterKeyPath(data.resolve("secrets/config.key").toString());

        Throwable thrown = catchThrowable(() -> new SecretCryptoService(props).encrypt("name", "value"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getCause()).isInstanceOf(ApiException.class)
                .hasMessageContaining("EXTERNAL_READ_ONLY");
        assertThat(source.resolve("config.key")).doesNotExist();
    }

    private static AppProperties externalProperties(Path source, Path data) {
        AppProperties props = new AppProperties();
        props.setSourceLibraryPath(source.toString());
        props.setDataPath(data.toString());
        props.setKtvLibraryPath(data.resolve("managed").toString());
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        return props;
    }

    private static void createDirectoryLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | SecurityException e) {
        } catch (IOException e) {
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
