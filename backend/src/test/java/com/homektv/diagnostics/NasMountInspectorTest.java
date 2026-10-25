package com.homektv.diagnostics;

import com.homektv.config.AppProperties;
import com.homektv.library.LibraryMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NasMountInspectorTest {

    @TempDir
    Path tempDir;

    @Test
    void readsOnlyMountInfoForAnExternalReadOnlyLibrary() throws Exception {
        Path mountInfo = Files.writeString(tempDir.resolve("mountinfo"),
                "40 29 0:44 / /source-music ro,nosuid - cifs //nas/music rw\n");
        AppProperties properties = properties(LibraryMode.EXTERNAL_READ_ONLY, "/source-music/karaoke");
        NasMountInspector inspector = new NasMountInspector(properties, mountInfo,
                path -> Files.readAllLines(path, StandardCharsets.UTF_8));

        assertThat(inspector.inspectConfiguredLibrary())
                .isEqualTo(new MountInfoParser.Inspection(MountInfoParser.Status.READ_ONLY,
                        "/source-music", "cifs"));
    }

    @Test
    void skipsMountInfoForManagedModeOrWhenTheFileIsNotRegular() {
        AtomicInteger reads = new AtomicInteger();
        Path mountInfo = tempDir.resolve("missing-mountinfo");
        NasMountInspector managed = new NasMountInspector(
                properties(LibraryMode.MANAGED, "/source-music"), mountInfo,
                path -> { reads.incrementAndGet(); return java.util.List.of(); });
        NasMountInspector missing = new NasMountInspector(
                properties(LibraryMode.EXTERNAL_READ_ONLY, "/source-music"), mountInfo,
                path -> { reads.incrementAndGet(); return java.util.List.of(); });

        assertThat(managed.inspectConfiguredLibrary().status()).isEqualTo(MountInfoParser.Status.UNKNOWN);
        assertThat(missing.inspectConfiguredLibrary().status()).isEqualTo(MountInfoParser.Status.UNKNOWN);
        assertThat(reads).hasValue(0);
    }

    @Test
    void convertsMountInfoReadFailuresToAnUnknownInspection() throws Exception {
        Path mountInfo = Files.writeString(tempDir.resolve("mountinfo"), "readable marker\n");
        AppProperties properties = properties(LibraryMode.EXTERNAL_READ_ONLY, "/source-music");
        NasMountInspector inspector = new NasMountInspector(properties, mountInfo,
                path -> { throw new IOException("synthetic read failure"); });

        assertThat(inspector.inspectConfiguredLibrary().status()).isEqualTo(MountInfoParser.Status.UNKNOWN);
    }

    private static AppProperties properties(LibraryMode mode, String sourcePath) {
        AppProperties properties = new AppProperties();
        properties.setLibraryMode(mode);
        properties.setSourceLibraryPath(sourcePath);
        return properties;
    }
}
