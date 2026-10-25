package com.homektv.web;

import com.homektv.library.AssetWriter;
import com.homektv.library.ArtistProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistAvatarControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsNotFoundForMissingProfilesBlankPathsAndMissingAssets() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        AssetWriter assets = mock(AssetWriter.class);
        ArtistAvatarController controller = new ArtistAvatarController(profiles, assets);
        when(profiles.find("missing")).thenReturn(Optional.empty());
        when(profiles.find("null-path")).thenReturn(Optional.of(profile(null)));
        when(profiles.find("blank-path")).thenReturn(Optional.of(profile("  ")));
        when(profiles.find("stale-path")).thenReturn(Optional.of(profile("avatars/stale.png")));
        when(assets.readableCachePath("avatars/stale.png")).thenReturn(Optional.empty());

        assertThat(controller.avatar("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.avatar("null-path").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.avatar("blank-path").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.avatar("stale-path").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(assets, never()).readableCachePath("  ");
    }

    @Test
    void servesOnlyResolvedApplicationCacheFilesAndFallsBackToJpegForUnknownTypes() throws Exception {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        AssetWriter assets = mock(AssetWriter.class);
        ArtistAvatarController controller = new ArtistAvatarController(profiles, assets);
        Path png = Files.write(tempDir.resolve("artist.png"), new byte[]{1, 2, 3});
        Path unknown = Files.write(tempDir.resolve("artist.unknown-ktv-format"), new byte[]{4, 5, 6});
        when(profiles.find("png-key")).thenReturn(Optional.of(profile("avatars/artist.png")));
        when(profiles.find("unknown-key")).thenReturn(Optional.of(profile("avatars/artist.unknown-ktv-format")));
        when(assets.readableCachePath("avatars/artist.png")).thenReturn(Optional.of(png));
        when(assets.readableCachePath("avatars/artist.unknown-ktv-format")).thenReturn(Optional.of(unknown));

        var pngResponse = controller.avatar("png-key");
        var fallbackResponse = controller.avatar("unknown-key");

        assertThat(pngResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pngResponse.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(fallbackResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fallbackResponse.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(pngResponse.getHeaders().getCacheControl()).contains("public");
    }

    private static ArtistProfileService.Profile profile(String avatarPath) {
        return new ArtistProfileService.Profile("artist-key", "歌手", "PERSON", avatarPath,
                null, null, "READY");
    }
}
