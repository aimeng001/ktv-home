package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.library.AdminService;
import com.homektv.library.LibraryMode;
import com.homektv.library.LibraryScanCoordinator;
import com.homektv.library.LibraryScanService;
import com.homektv.library.LibraryWatchService;
import com.homektv.library.MediaImportService;
import com.homektv.library.SettingService;
import com.homektv.library.SongMergeService;
import com.homektv.library.SongReparseService;
import com.homektv.library.TranscodeHardwareService;
import com.homektv.library.TranscodeService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminScanControllerTest {

    @Test
    void managedScanUsesSourceLibraryResultInsteadOfExternalLibraryQueue() {
        MediaImportService imports = mock(MediaImportService.class);
        MediaImportService.SourceScanResult expected = new MediaImportService.SourceScanResult(
                2, 1, 0, 0, 0, 1, 0);
        when(imports.scanSourceLibrary()).thenReturn(expected);
        LibraryScanService scanService = mock(LibraryScanService.class);
        AdminScanController controller = controller(managedProps(), scanService, imports,
                mock(SettingService.class), mock(TranscodeService.class));

        assertThat(controller.scan()).containsEntry("sourceScan", expected);
        verify(imports).scanSourceLibrary();
        verifyNoInteractions(scanService);
    }

    @Test
    void externalScanUsesCoordinatorWhenInstalled() {
        LibraryScanService scanService = mock(LibraryScanService.class);
        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        LibraryScanService.ScanProgress expected = idleScanProgress();
        when(coordinator.startScan()).thenReturn(expected);
        AdminScanController controller = controller(externalProps(), scanService,
                mock(MediaImportService.class), mock(SettingService.class), mock(TranscodeService.class));
        controller.setScanCoordinator(coordinator);

        assertThat(controller.scan()).containsEntry("libraryScan", expected);
        verify(coordinator).startScan();
        verifyNoInteractions(scanService);
    }

    @Test
    void managedStartScanDelegatesToSourceLibraryImporter() {
        AppProperties props = managedProps();
        MediaImportService imports = mock(MediaImportService.class);
        MediaImportService.SourceScanProgress expected = new MediaImportService.SourceScanProgress(
                true, 1, 0, "song.mp4", 0, 0, 0, 0, 0, 0, null, null);
        when(imports.startSourceScan()).thenReturn(expected);
        LibraryScanService scanService = mock(LibraryScanService.class);
        AdminScanController controller = controller(props, scanService, imports, mock(SettingService.class),
                mock(TranscodeService.class));

        assertThat(controller.startScan()).isSameAs(expected);
        verify(imports).startSourceScan();
        verifyNoInteractions(scanService);
    }

    @Test
    void externalStartScanUsesCoordinatorWhenInstalled() {
        AppProperties props = externalProps();
        LibraryScanService scanService = mock(LibraryScanService.class);
        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        LibraryScanService.ScanProgress expected = idleScanProgress();
        when(coordinator.startScan()).thenReturn(expected);
        AdminScanController controller = controller(props, scanService, mock(MediaImportService.class),
                mock(SettingService.class), mock(TranscodeService.class));
        controller.setScanCoordinator(coordinator);

        assertThat(controller.startScan()).isSameAs(expected);
        verify(coordinator).startScan();
        verifyNoInteractions(scanService);
    }

    @Test
    void externalStartScanFallsBackToScanServiceWhenCoordinatorIsAbsent() {
        LibraryScanService scanService = mock(LibraryScanService.class);
        LibraryScanService.ScanProgress expected = idleScanProgress();
        when(scanService.startScan()).thenReturn(expected);
        AdminScanController controller = controller(externalProps(), scanService,
                mock(MediaImportService.class), mock(SettingService.class), mock(TranscodeService.class));

        assertThat(controller.startScan()).isSameAs(expected);
        verify(scanService).startScan();
    }

    @Test
    void scanProgressSelectsExternalOrManagedProgressSource() {
        LibraryScanService scanService = mock(LibraryScanService.class);
        MediaImportService imports = mock(MediaImportService.class);
        LibraryScanService.ScanProgress external = idleScanProgress();
        MediaImportService.SourceScanProgress managed = new MediaImportService.SourceScanProgress(
                false, 2, 2, null, 2, 0, 0, 0, 0, 0, null, null);
        when(scanService.getScanProgress()).thenReturn(external);
        when(imports.getScanProgress()).thenReturn(managed);

        assertThat(controller(externalProps(), scanService, imports, mock(SettingService.class),
                mock(TranscodeService.class)).scanProgress()).isSameAs(external);
        assertThat(controller(managedProps(), scanService, imports, mock(SettingService.class),
                mock(TranscodeService.class)).scanProgress()).isSameAs(managed);
        verify(scanService).getScanProgress();
        verify(imports).getScanProgress();
    }

    @Test
    void rebindReturnsFalseWithoutCoordinatorAndDelegatesWhenInstalled() {
        AdminScanController controller = controller(externalProps(), mock(LibraryScanService.class),
                mock(MediaImportService.class), mock(SettingService.class), mock(TranscodeService.class));

        assertThat(controller.rebindExternalRoot()).containsEntry("rebound", false);

        LibraryScanCoordinator coordinator = mock(LibraryScanCoordinator.class);
        when(coordinator.rebindCurrentRoot()).thenReturn(true);
        controller.setScanCoordinator(coordinator);
        assertThat(controller.rebindExternalRoot()).containsEntry("rebound", true);
        verify(coordinator).rebindCurrentRoot();
    }

    @Test
    void sourceLibraryTranscodeDefaultsToNoIdsAndAllFalseWhenBodyIsAbsent() {
        MediaImportService imports = mock(MediaImportService.class);
        MediaImportService.TranscodeProgress expected = idleTranscodeProgress();
        when(imports.startPendingTranscode(List.of(), false)).thenReturn(expected);
        AdminScanController controller = controller(managedProps(), mock(LibraryScanService.class), imports,
                mock(SettingService.class), mock(TranscodeService.class));

        assertThat(controller.transcodeSourceLibrary(null)).isSameAs(expected);
        verify(imports).startPendingTranscode(List.of(), false);
    }

    @Test
    void sourceLibraryTranscodeForwardsSelectedIdsAndAllFlag() {
        MediaImportService imports = mock(MediaImportService.class);
        MediaImportService.TranscodeProgress expected = idleTranscodeProgress();
        List<Long> ids = List.of(4L, 9L);
        when(imports.startPendingTranscode(ids, true)).thenReturn(expected);
        AdminScanController controller = controller(managedProps(), mock(LibraryScanService.class), imports,
                mock(SettingService.class), mock(TranscodeService.class));

        assertThat(controller.transcodeSourceLibrary(new AdminScanController.SourceLibraryRequest(
                ids, true, "keyword", "PENDING", "video", false))).isSameAs(expected);
        verify(imports).startPendingTranscode(ids, true);
    }

    @Test
    void managedSourceLibraryForwardsFiltersAndReturnsTheRequestedPage() {
        MediaImportService imports = mock(MediaImportService.class);
        when(imports.listSourceLibrary("keyword", "FAILED", "audio", true, 2, 10))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 20));
        AdminScanController controller = controller(managedProps(), mock(LibraryScanService.class), imports,
                mock(SettingService.class), mock(TranscodeService.class));

        assertThat(controller.sourceLibrary("keyword", "FAILED", "audio", true, 2, 10))
                .containsEntry("content", List.of())
                .containsEntry("total", 20L)
                .containsEntry("page", 2)
                .containsEntry("totalPages", 2)
                .containsEntry("libraryMode", "MANAGED");
        verify(imports).listSourceLibrary("keyword", "FAILED", "audio", true, 2, 10);
    }

    @Test
    void deleteSourcesRequiresExplicitFilterWhenIdsAreEmpty() {
        MediaImportService imports = mock(MediaImportService.class);
        AdminScanController controller = controller(managedProps(), mock(LibraryScanService.class), imports,
                mock(SettingService.class), mock(TranscodeService.class));

        assertThatThrownBy(() -> controller.deleteSources(new AdminScanController.SourceLibraryRequest(
                List.of(), false, "", "", "", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("all=true");
        verifyNoInteractions(imports);
    }

    @Test
    void deleteSourcesUsesIdsBeforeFilterAndSupportsExplicitAllFilter() {
        MediaImportService imports = mock(MediaImportService.class);
        MediaImportService.DeleteSourcesResult byIds = new MediaImportService.DeleteSourcesResult(1, 1, 0, 0);
        MediaImportService.DeleteSourcesResult byFilter = new MediaImportService.DeleteSourcesResult(3, 2, 1, 0);
        List<Long> ids = List.of(6L);
        when(imports.deleteSources(ids)).thenReturn(byIds);
        when(imports.deleteSourcesByFilter("", "FAILED", "audio", true)).thenReturn(byFilter);
        AdminScanController controller = controller(managedProps(), mock(LibraryScanService.class), imports,
                mock(SettingService.class), mock(TranscodeService.class));

        assertThat(controller.deleteSources(new AdminScanController.SourceLibraryRequest(
                ids, true, "ignored", "ignored", "ignored", false))).isSameAs(byIds);
        assertThat(controller.deleteSources(new AdminScanController.SourceLibraryRequest(
                List.of(), true, "", "FAILED", "audio", true))).isSameAs(byFilter);
        verify(imports).deleteSources(ids);
        verify(imports).deleteSourcesByFilter("", "FAILED", "audio", true);
    }

    @Test
    void transcodeSettingsProbeHardwareOnlyWhenAccelerationIsRequested() {
        SettingService settings = mock(SettingService.class);
        LibraryWatchService watcher = mock(LibraryWatchService.class);
        TranscodeHardwareService hardware = mock(TranscodeHardwareService.class);
        when(settings.getEditable()).thenReturn(Map.of("transcode_hardware_acceleration", false));
        AppProperties props = managedProps();
        AdminScanController controller = new AdminScanController(mock(LibraryScanService.class), watcher,
                mock(AdminService.class), mock(MediaImportService.class), settings, mock(TranscodeService.class),
                hardware, mock(SongReparseService.class), mock(SongMergeService.class), props);

        assertThat(controller.putSettings(Map.of("transcode_hardware_acceleration", false)))
                .containsEntry("transcode_hardware_acceleration", false);
        verify(hardware, never()).requireAvailable(org.mockito.ArgumentMatchers.anyString());
        verify(settings).putEditable(Map.of("transcode_hardware_acceleration", false));
        verify(watcher).reloadFromSettings();
    }

    @Test
    void transcodeSettingsRequireConfiguredCodecBeforeSavingAcceleration() {
        SettingService settings = mock(SettingService.class);
        LibraryWatchService watcher = mock(LibraryWatchService.class);
        TranscodeHardwareService hardware = mock(TranscodeHardwareService.class);
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of(), List.of(), List.of(), false, "mkv", "hevc", "aac", false);
        when(settings.transcodePolicy()).thenReturn(policy);
        when(settings.getEditable()).thenReturn(Map.of("transcode_hardware_acceleration", true));
        AdminScanController controller = new AdminScanController(mock(LibraryScanService.class), watcher,
                mock(AdminService.class), mock(MediaImportService.class), settings, mock(TranscodeService.class),
                hardware, mock(SongReparseService.class), mock(SongMergeService.class), managedProps());

        controller.putSettings(Map.of("transcode_hardware_acceleration", true));

        verify(hardware).requireAvailable("hevc");
        verify(settings).putEditable(Map.of("transcode_hardware_acceleration", true));
        verify(watcher).reloadFromSettings();
    }

    @Test
    void songTranscodeCleansSourceOnlyWhenSettingEnablesIt() {
        long id = 12L;
        TranscodeService transcode = mock(TranscodeService.class);
        LibraryScanService scan = mock(LibraryScanService.class);
        MediaImportService imports = mock(MediaImportService.class);
        SettingService settings = mock(SettingService.class);
        TranscodeService.Result result = new TranscodeService.Result(3L, "converted.mp4", 800L);
        LibraryScanService.ScanResult scanResult = new LibraryScanService.ScanResult(1, 0, 0, 1, 0);
        when(transcode.transcodeSong(id)).thenReturn(result);
        when(scan.scanAll()).thenReturn(scanResult);
        when(settings.getAll()).thenReturn(Map.of(SettingService.DELETE_SOURCE_AFTER_TRANSCODE, true));
        when(imports.cleanupSongSource(id)).thenReturn(1);
        AdminScanController controller = controller(managedProps(), scan, imports, settings, transcode);

        assertThat(controller.transcode(id)).containsEntry("status", "completed")
                .containsEntry("sourceDeleted", 1).containsEntry("scan", scanResult);
        verify(imports).cleanupSongSource(id);
    }

    @Test
    void songTranscodeLeavesSourceAloneWhenCleanupSettingIsDisabled() {
        long id = 13L;
        TranscodeService transcode = mock(TranscodeService.class);
        LibraryScanService scan = mock(LibraryScanService.class);
        MediaImportService imports = mock(MediaImportService.class);
        SettingService settings = mock(SettingService.class);
        TranscodeService.Result result = new TranscodeService.Result(4L, "converted.mp4", 900L);
        when(transcode.transcodeSong(id)).thenReturn(result);
        when(scan.scanAll()).thenReturn(new LibraryScanService.ScanResult(1, 1, 0, 0, 0));
        when(settings.getAll()).thenReturn(Map.of(SettingService.DELETE_SOURCE_AFTER_TRANSCODE, false));
        AdminScanController controller = controller(managedProps(), scan, imports, settings, transcode);

        assertThat(controller.transcode(id)).containsEntry("sourceDeleted", 0);
        verify(imports, never()).cleanupSongSource(id);
    }

    @Test
    void vocalTrackConfirmationAcceptsNumbersAndTrimmedIntegerStrings() {
        AdminService admin = mock(AdminService.class);
        AdminScanController controller = new AdminScanController(mock(LibraryScanService.class),
                mock(LibraryWatchService.class), admin, mock(MediaImportService.class),
                mock(SettingService.class), mock(TranscodeService.class), mock(TranscodeHardwareService.class),
                mock(SongReparseService.class), mock(SongMergeService.class), managedProps());

        assertThat(controller.confirmVocalTrack(8L, Map.of("accompanimentIndex", 2)))
                .containsEntry("status", "confirmed").containsEntry("accompanimentIndex", 2);
        assertThat(controller.confirmVocalTrack(9L, Map.of("accompanimentIndex", " 3 ")))
                .containsEntry("status", "confirmed").containsEntry("accompanimentIndex", 3);
        verify(admin).confirmVocalTrack(8L, 2);
        verify(admin).confirmVocalTrack(9L, 3);
    }

    @Test
    void vocalTrackConfirmationDefaultsMissingIndexToOne() {
        AdminService admin = mock(AdminService.class);
        AdminScanController controller = new AdminScanController(mock(LibraryScanService.class),
                mock(LibraryWatchService.class), admin, mock(MediaImportService.class),
                mock(SettingService.class), mock(TranscodeService.class), mock(TranscodeHardwareService.class),
                mock(SongReparseService.class), mock(SongMergeService.class), managedProps());

        assertThat(controller.confirmVocalTrack(10L, Map.of()))
                .containsEntry("status", "confirmed").containsEntry("accompanimentIndex", 1);
        verify(admin).confirmVocalTrack(10L, 1);
    }

    @Test
    void vocalTrackConfirmationRejectsMalformedStringsAndUnsupportedTypes() {
        AdminScanController controller = controller(managedProps(), mock(LibraryScanService.class),
                mock(MediaImportService.class), mock(SettingService.class), mock(TranscodeService.class));
        Map<String, Object> nullIndex = new java.util.HashMap<>();
        nullIndex.put("accompanimentIndex", null);

        assertThatThrownBy(() -> controller.confirmVocalTrack(8L,
                Map.of("accompanimentIndex", "two")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("非负整数");
        assertThatThrownBy(() -> controller.confirmVocalTrack(8L, nullIndex))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("类型无效");
    }

    @Test
    void vocalTrackConfirmationRejectsNegativeIndexes() {
        AdminScanController controller = controller(managedProps(), mock(LibraryScanService.class),
                mock(MediaImportService.class), mock(SettingService.class), mock(TranscodeService.class));

        assertThatThrownBy(() -> controller.confirmVocalTrack(8L,
                Map.of("accompanimentIndex", -1)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能为负数");
    }

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

    private static AdminScanController controller(AppProperties props, LibraryScanService scanService,
                                                  MediaImportService imports, SettingService settings,
                                                  TranscodeService transcodeService) {
        return new AdminScanController(scanService, mock(LibraryWatchService.class), mock(AdminService.class),
                imports, settings, transcodeService, mock(TranscodeHardwareService.class),
                mock(SongReparseService.class), mock(SongMergeService.class), props);
    }

    private static AppProperties managedProps() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.MANAGED);
        return props;
    }

    private static AppProperties externalProps() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        return props;
    }

    private static LibraryScanService.ScanProgress idleScanProgress() {
        return new LibraryScanService.ScanProgress(false, 0, 0, null, 0, 0, 0, 0, null, null);
    }

    private static MediaImportService.TranscodeProgress idleTranscodeProgress() {
        return new MediaImportService.TranscodeProgress(false, 0, 0, 0, 0, 0, 0, 0,
                null, null, List.of(), null, null);
    }
}
