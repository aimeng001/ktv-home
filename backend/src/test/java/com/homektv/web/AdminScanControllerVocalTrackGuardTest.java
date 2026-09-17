package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.library.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AdminScanControllerVocalTrackGuardTest {

    private AdminScanController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminScanController(
                mock(LibraryScanService.class),
                mock(LibraryWatchService.class),
                mock(AdminService.class),
                mock(MediaImportService.class),
                mock(SettingService.class),
                mock(TranscodeService.class),
                mock(TranscodeHardwareService.class),
                mock(SongReparseService.class),
                mock(SongMergeService.class),
                new AppProperties());
    }

    @Test
    void confirmVocalTrack_withStringAccompanimentIndex_throwsApiException() {
        Map<String, Object> body = Map.of("accompanimentIndex", "invalid");

        assertThatThrownBy(() -> controller.confirmVocalTrack(10L, body))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_ARGUMENT");
                });
    }

    @Test
    void confirmVocalTrack_withNegativeAccompanimentIndex_throwsApiException() {
        Map<String, Object> body = Map.of("accompanimentIndex", -1);

        assertThatThrownBy(() -> controller.confirmVocalTrack(10L, body))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_ARGUMENT");
                });
    }
}
