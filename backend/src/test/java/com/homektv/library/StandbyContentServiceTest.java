package com.homektv.library;

import com.homektv.domain.PlayHistory;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StandbyContentServiceTest {

    @Test
    void blankLogoSettingDoesNotExposeALogoUrl() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("standby_logo_path", ""));
        StandbyContentService service = new StandbyContentService(settings,
                mock(SongRepository.class), mock(PlayHistoryRepository.class), mock(AssetWriter.class));

        assertThat(service.content()).containsEntry("logoUrl", null);
    }

    @Test
    void miniQrSettingIsIncludedInStandbyContent() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("mini_qr", false));
        StandbyContentService service = new StandbyContentService(settings,
                mock(SongRepository.class), mock(PlayHistoryRepository.class), mock(AssetWriter.class));

        assertThat(service.content()).containsEntry("miniQr", false);
    }

    @Test
    void replacingLogoSchedulesThePreviousAssetForCleanup() throws Exception {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("standby_logo_path", "standby/logo-old.png"));
        AssetWriter writer = mock(AssetWriter.class);
        when(writer.writeStandbyLogo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("png")))
                .thenReturn("standby/logo-new.png");
        AssetCleanupService cleanup = mock(AssetCleanupService.class);
        StandbyContentService service = new StandbyContentService(settings,
                mock(SongRepository.class), mock(PlayHistoryRepository.class), writer);
        service.setAssetCleanupService(cleanup);

        service.uploadLogo(new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3}));

        verify(cleanup).afterCommitIfUnreferenced("standby/logo-old.png");
    }
}
