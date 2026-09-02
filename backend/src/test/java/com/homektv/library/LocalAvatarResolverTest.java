package com.homektv.library;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAvatarResolverTest {

    @Test
    void matchesNamedArtistImagesInCandidateDirectoriesAndSetsAvatarReady(@TempDir Path tempDir) throws Exception {
        Path avatarsDir = tempDir.resolve("artist-packs");
        Files.createDirectories(avatarsDir);
        Files.write(avatarsDir.resolve("周杰伦.jpg"), new byte[]{1, 2, 3});
        Files.write(avatarsDir.resolve("1983.png"), new byte[]{4, 5, 6});
        Files.write(avatarsDir.resolve("Beyond.webp"), new byte[]{7, 8, 9});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());
        props.setAvatarLibraryPath(tempDir.resolve("empty-lib").toString());
        props.setSourceLibraryPath(tempDir.resolve("empty-src").toString());

        AssetWriter assetWriter = mock(AssetWriter.class);
        when(assetWriter.writeArtistCover(eq("zhoujielun"), any(), eq("jpg"))).thenReturn("artist-covers/zjl.jpg");
        when(assetWriter.writeArtistCover(eq("1983"), any(), eq("png"))).thenReturn("artist-covers/1983.png");
        when(assetWriter.writeArtistCover(eq("beyond"), any(), eq("webp"))).thenReturn("artist-covers/beyond.webp");

        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("zhoujielun", "周杰伦", "PERSON", null, null, null, "PENDING"),
                new ArtistProfileService.Profile("1983", "1983", "GROUP", null, null, null, "PENDING"),
                new ArtistProfileService.Profile("beyond", "Beyond", "GROUP", null, null, null, "PENDING"),
                new ArtistProfileService.Profile("zhangxueyou", "张学友", "PERSON", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);
        int matched = resolver.resolveAllCandidates();

        assertThat(matched).isEqualTo(3);
        verify(profileService).setAvatarReady("zhoujielun", "LOCAL_PACK", "artist-covers/zjl.jpg");
        verify(profileService).setAvatarReady("1983", "LOCAL_PACK", "artist-covers/1983.png");
        verify(profileService).setAvatarReady("beyond", "LOCAL_PACK", "artist-covers/beyond.webp");
    }

    @Test
    void matchesSmartAliasesWithParenthesesAndBracketsAndRootFiles(@TempDir Path tempDir) throws Exception {
        Path customDir = tempDir.resolve("my-custom-avatars");
        Files.createDirectories(customDir);
        Files.write(customDir.resolve("王力宏 (Leehom Wang).jpg"), new byte[]{1, 2, 3});
        Files.write(customDir.resolve("Beyond [乐队].png"), new byte[]{4, 5, 6});
        // Direct file in data directory root
        Files.write(tempDir.resolve("张学友.webp"), new byte[]{7, 8, 9});

        // Excluded directory that should be ignored
        Path excludedDir = tempDir.resolve("artist-covers");
        Files.createDirectories(excludedDir);
        Files.write(excludedDir.resolve("周杰伦.jpg"), new byte[]{9, 9, 9});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        AssetWriter assetWriter = mock(AssetWriter.class);
        when(assetWriter.writeArtistCover(any(), any(), any())).thenReturn("artist-covers/saved.jpg");

        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("wanglihong", "王力宏", "PERSON", null, null, null, "PENDING"),
                new ArtistProfileService.Profile("beyond", "Beyond", "GROUP", null, null, null, "PENDING"),
                new ArtistProfileService.Profile("zhangxueyou", "张学友", "PERSON", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);
        int matched = resolver.resolveAllCandidates();

        assertThat(matched).isEqualTo(3);
        verify(profileService).setAvatarReady("wanglihong", "LOCAL_PACK", "artist-covers/saved.jpg");
        verify(profileService).setAvatarReady("beyond", "LOCAL_PACK", "artist-covers/saved.jpg");
        verify(profileService).setAvatarReady("zhangxueyou", "LOCAL_PACK", "artist-covers/saved.jpg");
    }

    @Test
    void skipsFilesExceedingMaxFileSize(@TempDir Path tempDir) throws Exception {
        Path avatarsDir = tempDir.resolve("avatars");
        Files.createDirectories(avatarsDir);
        Path bigFile = avatarsDir.resolve("张学友.jpg");
        byte[] chunk = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(bigFile)) {
            for (int i = 0; i < 16; i++) {
                out.write(chunk);
            }
        }

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        AssetWriter assetWriter = mock(AssetWriter.class);
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("zhangxueyou", "张学友", "PERSON", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);
        int matched = resolver.resolveAllCandidates();

        assertThat(matched).isEqualTo(0);
    }

    @Test
    void skipsPlaceholderArtistsAndMissingFiles(@TempDir Path tempDir) throws Exception {
        Path avatarsDir = tempDir.resolve("avatars");
        Files.createDirectories(avatarsDir);
        Files.write(avatarsDir.resolve("群星.jpg"), new byte[]{1, 2});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        AssetWriter assetWriter = mock(AssetWriter.class);
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("qunxing", "群星", "VARIOUS", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);
        int matched = resolver.resolveAllCandidates();

        assertThat(matched).isEqualTo(0);
    }

    @Test
    void collectCandidateDirectories_doesNotDuplicateNestedDataDirectories(@TempDir Path tempDir) throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path subDir = dataDir.resolve("artist-packs");
        Files.createDirectories(subDir);

        AppProperties props = new AppProperties();
        props.setDataPath(dataDir.toString());

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, null, null);
        List<Path> candidates = resolver.collectCandidateDirectories();

        long dataDirCount = candidates.stream()
                .filter(p -> p.equals(dataDir) || p.startsWith(dataDir))
                .count();
        assertThat(dataDirCount).isEqualTo(1);
    }
}
