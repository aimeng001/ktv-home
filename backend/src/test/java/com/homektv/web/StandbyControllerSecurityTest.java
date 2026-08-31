package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.library.AssetWriter;
import com.homektv.library.SettingService;
import com.homektv.library.StandbyContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StandbyControllerSecurityTest {
    @TempDir Path tempDir;
    private SettingService settingService;
    private StandbyContentService standbyService;
    private AssetWriter assetWriter;
    private StandbyController controller;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());
        props.setSourceLibraryPath(tempDir.resolve("music").toString());
        Files.createDirectories(tempDir.resolve("secrets"));
        Files.createDirectories(tempDir.resolve("standby"));
        Files.writeString(tempDir.resolve("secrets/config.key"), "SUPER_SECRET_KEY");

        settingService = mock(SettingService.class);
        standbyService = mock(StandbyContentService.class);
        assetWriter = new AssetWriter(props);
        controller = new StandbyController(standbyService, settingService, assetWriter);
    }

    @Test
    void logoEndpoint_refusesArbitraryDataFiles() {
        when(settingService.getAll()).thenReturn(Map.of("standby_logo_path", "secrets/config.key"));
        ResponseEntity<Resource> res = controller.logo();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void logoEndpoint_refusesTraversalPath() {
        when(settingService.getAll()).thenReturn(Map.of("standby_logo_path", "standby/../secrets/config.key"));
        ResponseEntity<Resource> res = controller.logo();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void logoEndpoint_servesValidStandbyImage() throws Exception {
        Path logo = tempDir.resolve("standby/logo-1.png");
        Files.write(logo, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        when(settingService.getAll()).thenReturn(Map.of("standby_logo_path", "standby/logo-1.png"));
        ResponseEntity<Resource> res = controller.logo();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
