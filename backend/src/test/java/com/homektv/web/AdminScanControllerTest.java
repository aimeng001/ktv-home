package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.library.AdminService;
import com.homektv.library.LibraryMode;
import com.homektv.library.LibraryScanService;
import com.homektv.library.LibraryWatchService;
import com.homektv.library.MediaImportService;
import com.homektv.library.SettingService;
import com.homektv.library.SongMergeService;
import com.homektv.library.SongReparseService;
import com.homektv.library.TranscodeHardwareService;
import com.homektv.library.TranscodeService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminScanControllerTest {

    @Test
    void externalSourceLibraryEndpointDoesNotExposeManagedImportRecords() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        MediaImportService mediaImportService = mock(MediaImportService.class);
        AdminScanController controller = new AdminScanController(
                mock(LibraryScanService.class),
                mock(LibraryWatchService.class),
                mock(AdminService.class),
                mediaImportService,
                mock(SettingService.class),
                mock(TranscodeService.class),
                mock(TranscodeHardwareService.class),
                mock(SongReparseService.class),
                mock(SongMergeService.class),
                props);

        Map<String, Object> response = controller.sourceLibrary("", "", "", null, 0, 20);

        assertThat(response).containsEntry("libraryMode", "EXTERNAL_READ_ONLY")
                .containsEntry("total", 0L)
                .containsEntry("totalPages", 0);
        assertThat(response.get("content")).isEqualTo(java.util.List.of());
        verifyNoInteractions(mediaImportService);
    }

    @Test
    void externalScanEndpointStartsFastIndexAndBackgroundProbeQueue() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        LibraryScanService scanService = mock(LibraryScanService.class);
        LibraryScanService.ScanProgress progress = new LibraryScanService.ScanProgress(
                true, 0, 0, null, 0, 0, 0, 0, null, null);
        when(scanService.startScan()).thenReturn(progress);
        AdminScanController controller = new AdminScanController(
                scanService,
                mock(LibraryWatchService.class),
                mock(AdminService.class),
                mock(MediaImportService.class),
                mock(SettingService.class),
                mock(TranscodeService.class),
                mock(TranscodeHardwareService.class),
                mock(SongReparseService.class),
                mock(SongMergeService.class),
                props);

        Map<String, Object> response = controller.scan();

        assertThat(response).containsEntry("libraryScan", progress);
        verify(scanService).startScan();
        verify(scanService, never()).scanAll();
    }
}
