package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ExternalReadOnlyWriteGuardsTest {

    @TempDir
    Path tempDir;

    @Mock
    private FFprobeService ffprobe;
    @Mock
    private MediaImportRecordRepository importRepository;
    @Mock
    private SongFileRepository songFileRepository;
    @Mock
    private LibraryScanService scanService;
    @Mock
    private SettingService settingService;
    @Mock
    private MediaTranscoder mediaTranscoder;

    private AppProperties props;
    private MediaImportService mediaImportService;
    private Path sourceFile;

    @BeforeEach
    void setUp() throws Exception {
        Path sourceDir = Files.createDirectory(tempDir.resolve("nas-copy"));
        sourceFile = sourceDir.resolve("source.mkv");
        Files.write(sourceFile, new byte[]{0x10, 0x20, 0x30});
        props = new AppProperties();
        props.setSourceLibraryPath(sourceDir.toString());
        props.setKtvLibraryPath(tempDir.resolve("managed").toString());
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        mediaImportService = new MediaImportService(props, ffprobe, new FileHashService(),
                importRepository, songFileRepository, scanService, settingService, mediaTranscoder);
    }

    @AfterEach
    void tearDown() {
        mediaImportService.shutdown();
    }

    @Test
    void sourceImportAndCleanupOperationsAreRejectedBeforeTheyCanWrite() throws Exception {
        assertReadOnly(() -> mediaImportService.scanSourceLibrary());
        assertReadOnly(() -> mediaImportService.startSourceScan());
        assertReadOnly(() -> mediaImportService.startPendingTranscode(List.of(1L), false));
        assertReadOnly(() -> mediaImportService.prioritizeTranscode(1L));
        assertReadOnly(() -> mediaImportService.deleteSources(List.of(1L)));
        assertReadOnly(() -> mediaImportService.cleanupImportedSources());
        assertReadOnly(() -> mediaImportService.cleanupSongSource(1L));
        assertReadOnly(() -> mediaImportService.deleteSourcesByFilter(null, null, null, null));
        assertReadOnly(() -> mediaImportService.deleteSource(1L));

        verifyNoInteractions(importRepository, songFileRepository, ffprobe, scanService,
                settingService, mediaTranscoder);
        org.assertj.core.api.Assertions.assertThat(Files.readAllBytes(sourceFile))
                .containsExactly((byte) 0x10, (byte) 0x20, (byte) 0x30);
        org.assertj.core.api.Assertions.assertThat(tempDir.resolve("managed").resolve("source.mkv"))
                .doesNotExist();
    }

    @Test
    void standaloneSongTranscodeIsRejected() {
        TranscodeService service = new TranscodeService(songFileRepository, props);

        assertReadOnly(() -> service.transcodeSong(1L));

        verifyNoInteractions(songFileRepository);
    }

    @Test
    void externalSongFileCannotBeTranscodedAfterSwitchingBackToManagedMode() {
        props.setLibraryMode(LibraryMode.MANAGED);
        SongFile externalFile = new SongFile();
        externalFile.setSongId(1L);
        externalFile.setFilePath(sourceFile.toString());
        externalFile.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        externalFile.setPriority(1);
        when(songFileRepository.findBySongIdOrderByPriorityDesc(1L)).thenReturn(List.of(externalFile));

        TranscodeService service = new TranscodeService(songFileRepository, props);

        assertReadOnly(() -> service.transcodeSong(1L));
    }

    @Test
    void deletingA曲库SongIsRejected() {
        SongRepository songs = mock(SongRepository.class);
        SongFileRepository files = mock(SongFileRepository.class);
        AdminService service = new AdminService(songs, files, mock(PlayHistoryRepository.class),
                mock(WsBroadcaster.class), mock(AssetWriter.class), mock(QueueItemRepository.class),
                mock(PlayerStateRepository.class), props);

        assertReadOnly(() -> service.deleteSong(1L));

        verifyNoInteractions(songs, files);
    }

    @Test
    void mediaTranscoderRejectsSourceTranscodeInExternalMode() {
        MediaTranscoder service = new MediaTranscoder(mock(TranscodeHardwareService.class),
                "ffmpeg", props);
        Path source = tempDir.resolve("nas-copy").resolve("source.mkv");
        Path output = tempDir.resolve("managed").resolve("output.mkv");

        assertReadOnly(() -> service.transcode(source, output,
                new SettingService.TranscodePolicy(List.of("mkv"), List.of("h264"), List.of("aac"),
                        false, "mkv", "h264", "aac", false), true));
    }

    private static void assertReadOnly(Runnable operation) {
        assertThatThrownBy(() -> operation.run())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("EXTERNAL_READ_ONLY");
    }
}
