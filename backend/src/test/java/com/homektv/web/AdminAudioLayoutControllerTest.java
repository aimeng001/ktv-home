package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.library.AdminService;
import com.homektv.library.LibraryScanService;
import com.homektv.library.LibraryWatchService;
import com.homektv.library.MediaImportService;
import com.homektv.library.SettingService;
import com.homektv.library.SongMergeService;
import com.homektv.library.SongReparseService;
import com.homektv.library.TranscodeHardwareService;
import com.homektv.library.TranscodeService;
import com.homektv.web.dto.AudioLayoutDto;
import com.homektv.web.dto.AudioLayoutUpdateRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAudioLayoutControllerTest {

    @Test
    void exposesUpdateAndSwapAsFileScopedDatabaseOperations() {
        AdminService admin = mock(AdminService.class);
        AdminScanController controller = controller(admin);
        AudioLayoutUpdateRequest request = new AudioLayoutUpdateRequest(
                AudioLayout.DUAL_CHANNEL, null, null, AudioChannel.LEFT, AudioChannel.RIGHT);
        AudioLayoutDto dto = new AudioLayoutDto(AudioLayout.DUAL_CHANNEL, null, null,
                AudioChannel.LEFT, AudioChannel.RIGHT);
        when(admin.updateAudioLayout(12L, request)).thenReturn(dto);
        when(admin.swapAudioLayout(12L)).thenReturn(dto);

        assertThat(controller.updateAudioLayout(12L, request)).isSameAs(dto);
        assertThat(controller.swapAudioLayout(12L)).isSameAs(dto);
        verify(admin).updateAudioLayout(12L, request);
        verify(admin).swapAudioLayout(12L);
    }

    private static AdminScanController controller(AdminService admin) {
        return new AdminScanController(mock(LibraryScanService.class), mock(LibraryWatchService.class),
                admin, mock(MediaImportService.class), mock(SettingService.class), mock(TranscodeService.class),
                mock(TranscodeHardwareService.class), mock(SongReparseService.class), mock(SongMergeService.class),
                new AppProperties());
    }
}
