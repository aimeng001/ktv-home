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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminScanControllerDeleteGuardTest {

    private MediaImportService mediaImportService;
    private AdminScanController controller;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.MANAGED);
        mediaImportService = mock(MediaImportService.class);
        controller = new AdminScanController(
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
    }

    @Test
    void deleteSources_withEmptyIdsAndAllFalse_throwsApiException() {
        var request = new AdminScanController.SourceLibraryRequest(
                List.of(), false, null, null, null, null);

        assertThatThrownBy(() -> controller.deleteSources(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.getCode()).isEqualTo("DELETE_FILTER_REQUIRED");
                });

        verify(mediaImportService, never()).deleteSources(any());
        verify(mediaImportService, never()).deleteSourcesByFilter(any(), any(), any(), any());
    }

    @Test
    void deleteSources_withAllTrue_callsFilterDelete() {
        when(mediaImportService.deleteSourcesByFilter("rock", "pending", "copy", false))
                .thenReturn(new MediaImportService.DeleteSourcesResult(5, 5, 0, 0));

        var request = new AdminScanController.SourceLibraryRequest(
                List.of(), true, "rock", "pending", "copy", false);

        var result = controller.deleteSources(request);

        assertThat(result.requested()).isEqualTo(5);
        verify(mediaImportService).deleteSourcesByFilter("rock", "pending", "copy", false);
        verify(mediaImportService, never()).deleteSources(any());
    }

    @Test
    void deleteSources_withIds_bypassesGuard() {
        when(mediaImportService.deleteSources(List.of(10L, 20L)))
                .thenReturn(new MediaImportService.DeleteSourcesResult(2, 2, 0, 0));

        var request = new AdminScanController.SourceLibraryRequest(
                List.of(10L, 20L), false, null, null, null, null);

        var result = controller.deleteSources(request);

        assertThat(result.requested()).isEqualTo(2);
        verify(mediaImportService).deleteSources(List.of(10L, 20L));
        verify(mediaImportService, never()).deleteSourcesByFilter(any(), any(), any(), any());
    }
}
