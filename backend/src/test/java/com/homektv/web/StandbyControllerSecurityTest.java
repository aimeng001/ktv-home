package com.homektv.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.library.AssetWriter;
import com.homektv.library.SettingService;
import com.homektv.library.StandbyContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StandbyControllerSecurityTest {
    @TempDir Path tempDir;
    private final Map<String, Object> settings = new HashMap<>();
    private Map<String, Object> content;
    private StubStandbyContentService standbyService;
    private StandbyController controller;

    @BeforeEach
    void setUp() throws Exception {
        settings.clear();
        content = Map.of("welcomeText", "今晚开唱", "songs", java.util.List.of());
        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());
        props.setSourceLibraryPath(tempDir.resolve("music").toString());
        Files.createDirectories(tempDir.resolve("secrets"));
        Files.createDirectories(tempDir.resolve("standby"));
        Files.writeString(tempDir.resolve("secrets/config.key"), "SUPER_SECRET_KEY");

        StubSettingService settingService = new StubSettingService();
        AssetWriter assetWriter = new AssetWriter(props);
        standbyService = new StubStandbyContentService(settingService, assetWriter);
        controller = new StandbyController(standbyService, settingService, assetWriter);
    }

    @Test
    void logoEndpointRefusesArbitraryDataFilesAndTraversalPaths() {
        settings.put("standby_logo_path", "secrets/config.key");
        assertThat(controller.logo().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        settings.put("standby_logo_path", "standby/../secrets/config.key");
        assertThat(controller.logo().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void logoEndpointReturnsNotFoundWhenNoPathIsConfigured() {
        ResponseEntity<Resource> response = controller.logo();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void logoEndpointServesSupportedImageTypesWithTheirCorrectContentTypes() throws Exception {
        assertServedAs("logo-1.png", MediaType.IMAGE_PNG);
        assertServedAs("logo-2.webp", MediaType.parseMediaType("image/webp"));
        assertServedAs("logo-3.jpg", MediaType.IMAGE_JPEG);
        assertServedAs("logo-4.jpeg", MediaType.IMAGE_JPEG);
    }

    @Test
    void logoEndpointRejectsUnsupportedTypesAndDirectories() throws Exception {
        Files.write(tempDir.resolve("standby/logo.gif"), new byte[]{1});
        settings.put("standby_logo_path", "standby/logo.gif");
        assertThat(controller.logo().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Files.createDirectories(tempDir.resolve("standby/folder"));
        settings.put("standby_logo_path", "standby/folder");
        assertThat(controller.logo().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void contentAndUploadEndpointsDelegateAndReturnTheLatestContent() {
        assertThat(controller.content()).isSameAs(content);
        MockMultipartFile upload = new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3});

        assertThat(controller.uploadLogo(upload)).isSameAs(content);
        assertThat(standbyService.uploaded).isSameAs(upload);
        assertThat(standbyService.contentCalls).isEqualTo(2);
    }

    private void assertServedAs(String fileName, MediaType expectedType) throws Exception {
        Path path = tempDir.resolve("standby").resolve(fileName);
        byte[] expectedContent = new byte[]{1, 2, 3};
        Files.write(path, expectedContent);
        settings.put("standby_logo_path", "standby/" + fileName);

        ResponseEntity<Resource> response = controller.logo();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(expectedType);
        assertThat(response.getBody()).isNotNull();
        Path servedPath = response.getBody().getFile().toPath();
        assertThat(Files.isSameFile(servedPath, path)).isTrue();
        assertThat(Files.readAllBytes(servedPath)).containsExactly(expectedContent);
    }

    private final class StubSettingService extends SettingService {
        private StubSettingService() {
            super(null, new ObjectMapper());
        }

        @Override
        public Map<String, Object> getAll() {
            return settings;
        }
    }

    private final class StubStandbyContentService extends StandbyContentService {
        private int contentCalls;
        private MultipartFile uploaded;

        private StubStandbyContentService(SettingService settingService, AssetWriter assetWriter) {
            super(settingService, null, null, assetWriter);
        }

        @Override
        public Map<String, Object> content() {
            contentCalls++;
            return content;
        }

        @Override
        public String uploadLogo(MultipartFile file) {
            uploaded = file;
            return "standby/logo.png";
        }
    }
}
