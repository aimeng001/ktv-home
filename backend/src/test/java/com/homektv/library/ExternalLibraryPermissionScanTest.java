package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.media.FFprobeService;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalLibraryPermissionScanTest {

    @TempDir
    Path tempDir;

    @Mock
    private FFprobeService ffprobe;
    @Mock
    private TagReader tagReader;
    @Mock
    private SongRepository songRepository;
    @Mock
    private SongFileRepository songFileRepository;

    private LibraryScanService scanService;
    private Path sourceDir;
    private AppProperties props;

    @BeforeEach
    void setUp() throws IOException {
        sourceDir = Files.createDirectory(tempDir.resolve("external-source"));
        props = new AppProperties();
        props.setSourceLibraryPath(sourceDir.toString());
        props.setKtvLibraryPath(tempDir.resolve("managed").toString());
        props.setDataPath(tempDir.resolve("data").toString());
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);

        lenient().when(songRepository.findAll()).thenReturn(List.of());
        lenient().when(songRepository.findDistinctArtistByStatus(anyString())).thenReturn(List.of());
        lenient().when(songFileRepository.findMaxIdByFileRole(anyString())).thenReturn(0L);
        lenient().when(songFileRepository.countByFileRoleAndProbePendingTrue(anyString())).thenReturn(0L);

        InMemoryLibraryScanSeenPathStore seenStore = new InMemoryLibraryScanSeenPathStore(songFileRepository, songRepository);
        scanService = new LibraryScanService(
                props,
                ffprobe,
                tagReader,
                songRepository,
                songFileRepository,
                null,
                seenStore
        );
    }

    @Test
    void cleanScanReportsCompletedState() {
        scanService.startScan();

        for (int i = 0; i < 50; i++) {
            if (!scanService.getScanProgress().running()) break;
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }

        LibraryScanService.ScanProgress progress = scanService.getScanProgress();
        assertThat(progress.running()).isFalse();
        assertThat(progress.state()).isEqualTo(LibraryScanService.ScanState.COMPLETED);
        assertThat(progress.failedPaths()).isEqualTo(0);
    }

    @Test
    void asynchronousRuntimeFailureIsReportedAsFailedWithErrorCode() throws Exception {
        props.setSourceLibraryPath(tempDir.resolve("non-existing-root-path").toString());

        scanService.startScan();

        for (int i = 0; i < 50; i++) {
            if (!scanService.getScanProgress().running()) break;
            Thread.sleep(50);
        }

        LibraryScanService.ScanProgress progress = scanService.getScanProgress();
        assertThat(progress.running()).isFalse();
        assertThat(progress.state()).isEqualTo(LibraryScanService.ScanState.FAILED);
        assertThat(progress.errorCode()).isNotBlank();
        assertThat(progress.errorMessage()).isNotBlank();
    }
}
