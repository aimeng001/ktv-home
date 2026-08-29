package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in end-to-end scan stress test. It exercises the real scanAll() pipeline
 * against a temporary external library while replacing only the expensive
 * ffprobe/tag process boundaries with deterministic fakes.
 */
@SpringBootTest
@Testcontainers
@Import(LargeExternalLibraryScanTest.TestDoubles.class)
@EnabledIfSystemProperty(named = "runLargeLibraryScanTest", matches = "true")
class LargeExternalLibraryScanTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ktv")
            .withUsername("ktv")
            .withPassword("ktv");

    private static final Path libraryDir;
    private static final Path dataDir;
    private static final int rows;

    static {
        try {
            libraryDir = Files.createTempDirectory("ktv-large-external-library");
            dataDir = Files.createTempDirectory("ktv-large-external-data");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
        Integer configuredRows = Integer.getInteger("scanRows");
        if (configuredRows == null) configuredRows = Integer.getInteger("largeLibraryRows", 20_000);
        rows = Math.max(1_000, configuredRows);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("app.source-library-path", libraryDir::toString);
        registry.add("app.ktv-library-path", () -> libraryDir.resolve("managed").toString());
        registry.add("app.library-mode", () -> "EXTERNAL_READ_ONLY");
        registry.add("app.data-path", dataDir::toString);
    }

    @BeforeAll
    static void createSyntheticLibrary() throws IOException {
        for (int index = 1; index <= rows; index++) {
            Files.createFile(libraryDir.resolve(
                    "stress-artist-%05d-stress-title-%05d-国语-流行.mkv".formatted(index, index)));
        }
    }

    @AfterAll
    static void removeSyntheticLibrary() throws IOException {
        removeTree(libraryDir);
        removeTree(dataDir);
    }

    private static void removeTree(Path root) throws IOException {
        if (root == null || Files.notExists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new IllegalStateException("unable to clean synthetic library " + path, failure);
                }
            });
        }
    }

    @Autowired
    private LibraryScanService scanService;
    @Autowired
    private FFprobeService ffprobe;

    @Test
    void realScanAllHandlesLargeExternalLibraryAndNoChangeRescanDoesNotProbeAgain() {
        LibraryScanService.ScanResult first = scanService.scanAll();
        assertThat(first.scanned()).isEqualTo(rows);
        assertThat(first.fastIndexed()).isEqualTo(rows);
        assertThat(first.probeCalls()).isEqualTo(rows);

        clearInvocations(ffprobe);
        LibraryScanService.ScanResult second = scanService.scanAll();
        LibraryScanService.ScanResult third = scanService.scanAll();

        assertThat(second.scanned()).isEqualTo(rows);
        assertThat(second.probeCalls()).isZero();
        assertThat(third.probeCalls()).isZero();
        assertThat(second.missing()).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDoubles {
        @Bean
        @Primary
        FFprobeService ffprobe() {
            FFprobeService fake = mock(FFprobeService.class);
            when(fake.probe(any(Path.class))).thenReturn(
                    new MediaProbe(180_000, 1, 0, true, "1920x1080"));
            return fake;
        }

        @Bean
        @Primary
        TagReader tagReader() {
            TagReader fake = mock(TagReader.class);
            when(fake.read(any())).thenReturn(new TagInfo());
            return fake;
        }
    }
}
