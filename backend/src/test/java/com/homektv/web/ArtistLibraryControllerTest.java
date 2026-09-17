package com.homektv.web;

import com.homektv.library.ArtistLibraryService;
import com.homektv.library.ArtistAvatarJobService;
import com.homektv.library.ArtistProfileService;
import com.homektv.library.AssetWriter;
import com.homektv.library.LocalAvatarResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistLibraryControllerTest {

    @Test
    void uploadAvatarSavesFileAndMarksProfileReady() throws Exception {
        ArtistLibraryService libraryService = mock(ArtistLibraryService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        AssetWriter assetWriter = mock(AssetWriter.class);
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);

        when(assetWriter.writeArtistCover(eq("zhoujielun"), any(), eq("jpg")))
                .thenReturn("artist-covers/zjl.jpg");

        ArtistLibraryController controller = new ArtistLibraryController(
                libraryService, localAvatarResolver, assetWriter, profileService, avatarJobs);

        MockMultipartFile file = new MockMultipartFile(
                "file", "jay.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Map<String, Object> result = controller.uploadAvatar("zhoujielun", file);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("avatarUrl")).isEqualTo("/api/artists/avatar?key=zhoujielun");

        verify(profileService).setAvatarReady("zhoujielun", "CUSTOM_UPLOAD", "artist-covers/zjl.jpg");
    }

    @Test
    void uploadAvatarRejectsEmptyFile() {
        ArtistLibraryService libraryService = mock(ArtistLibraryService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        AssetWriter assetWriter = mock(AssetWriter.class);
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);

        ArtistLibraryController controller = new ArtistLibraryController(
                libraryService, localAvatarResolver, assetWriter, profileService, avatarJobs);

        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> controller.uploadAvatar("zhoujielun", file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void uploadAvatarRejectsInvalidExtension() {
        ArtistLibraryService libraryService = mock(ArtistLibraryService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        AssetWriter assetWriter = mock(AssetWriter.class);
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);

        ArtistLibraryController controller = new ArtistLibraryController(
                libraryService, localAvatarResolver, assetWriter, profileService, avatarJobs);

        MockMultipartFile file = new MockMultipartFile(
                "file", "malicious.exe", "application/octet-stream", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> controller.uploadAvatar("zhoujielun", file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("格式图片");
    }

    @Test
    void localScanEnqueuesOnlyAfterLocalResolutionCompletes() {
        ArtistLibraryService libraryService = mock(ArtistLibraryService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        AssetWriter assetWriter = mock(AssetWriter.class);
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);
        when(localAvatarResolver.resolveAllCandidates()).thenReturn(3);

        ArtistLibraryController controller = new ArtistLibraryController(
                libraryService, localAvatarResolver, assetWriter, profileService, avatarJobs);

        Map<String, Object> result = controller.scanLocalAvatars();

        assertThat(result.get("matched")).isEqualTo(3);
        verify(avatarJobs, times(1)).enqueuePendingProfilesAfterCommit();
    }

    @Test
    void retryEndpointDelegatesToBoundedServerSideQueue() {
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);
        when(avatarJobs.resetAndEnqueueMissingProfiles()).thenReturn(Map.of(
                "queued", 2, "alreadyPending", 1, "nextWorkAt", "2026-09-17T00:00:00Z"));
        ArtistLibraryController controller = new ArtistLibraryController(
                mock(ArtistLibraryService.class), mock(LocalAvatarResolver.class),
                mock(AssetWriter.class), mock(ArtistProfileService.class), avatarJobs);

        assertThat(controller.retryMissingAvatars().get("queued")).isEqualTo(2);
        verify(avatarJobs).resetAndEnqueueMissingProfiles();
    }
}
