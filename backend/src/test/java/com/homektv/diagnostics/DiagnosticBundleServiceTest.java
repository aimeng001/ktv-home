package com.homektv.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticBundleServiceTest {

    @TempDir Path tempDir;

    @Test
    void bundleIsBoundedUsesSafeEntryNamesAndRedactsLogContent() throws Exception {
        Files.writeString(tempDir.resolve("server.log"),
                "Authorization: Bearer top-secret\nhttps://host/x?api_key=query-secret");
        Files.write(tempDir.resolve("huge.log"), new byte[DiagnosticBundleService.MAX_LOG_BYTES + 10]);
        DiagnosticBundleService service = new DiagnosticBundleService(new ObjectMapper());

        byte[] zip = service.create(tempDir, Map.of("version", "1.0.0"));

        assertThat(zip.length).isLessThanOrEqualTo(DiagnosticBundleService.MAX_BUNDLE_BYTES);
        StringBuilder contents = new StringBuilder();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            int count = 0;
            while ((entry = input.getNextEntry()) != null) {
                count++;
                assertThat(entry.getName()).doesNotContain("..", "\\");
                contents.append(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            assertThat(count).isLessThanOrEqualTo(DiagnosticBundleService.MAX_ENTRIES);
        }
        assertThat(contents).contains("[REDACTED]");
        assertThat(contents).doesNotContain("top-secret", "query-secret");
    }
}
