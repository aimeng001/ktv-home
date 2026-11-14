package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.musicsource.CoverImageNormalizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        Files.write(customDir.resolve("Solo [ ].jpg"), new byte[]{2, 4, 6});
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
    void boundedImageReadRejectsAFileThatGrewAfterTheInitialSizeCheck(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("grown-avatar.jpg");
        Files.write(image, new byte[(int) LocalAvatarResolver.MAX_FILE_SIZE + 1]);

        assertThat(LocalAvatarResolver.readBounded(image)).isNull();
    }

    @Test
    void boundedImageReadRejectsNullAndDirectories(@TempDir Path tempDir) throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("not-an-image"));

        assertThat(LocalAvatarResolver.readBounded(null)).isNull();
        assertThat(LocalAvatarResolver.readBounded(directory)).isNull();
    }

    @Test
    void rejectsAvatarFileSymlinkPointingOutsideCandidateDirectory(@TempDir Path tempDir) throws Exception {
        Path candidateDir = tempDir.resolve("avatars");
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(candidateDir);
        Files.createDirectories(outsideDir);
        Path outsideImage = outsideDir.resolve("张学友.jpg");
        Files.write(outsideImage, new byte[]{1, 2, 3});

        Path linkedImage = candidateDir.resolve("张学友.jpg");
        try {
            Files.createSymbolicLink(linkedImage, outsideImage);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable in this test environment");
        }

        AppProperties props = new AppProperties();
        props.setDataPath(candidateDir.toString());

        AssetWriter assetWriter = mock(AssetWriter.class);
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("zhangxueyou", "张学友", "PERSON", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);

        assertThat(resolver.resolveAllCandidates()).isZero();
        verify(assetWriter, never()).writeArtistCover(any(), any(), any());
        verify(profileService, never()).setAvatarReady(any(), any(), any());
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

    @Test
    void collectCandidateDirectoriesIncludesOnlyExistingRootsAndKnownSourceFolders(@TempDir Path tempDir)
            throws Exception {
        Path avatarLibrary = Files.createDirectory(tempDir.resolve("avatar-library"));
        Path dataDirectory = Files.createDirectory(tempDir.resolve("data"));
        Path sourceDirectory = Files.createDirectory(tempDir.resolve("source"));
        Path sourceAvatars = Files.createDirectory(sourceDirectory.resolve("avatars"));
        Path sourcePortraits = Files.createDirectory(sourceDirectory.resolve("歌手写真"));

        AppProperties props = new AppProperties();
        props.setAvatarLibraryPath(avatarLibrary.toString());
        props.setDataPath(dataDirectory.toString());
        props.setSourceLibraryPath(sourceDirectory.toString());

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, null, null);

        assertThat(resolver.collectCandidateDirectories())
                .containsExactly(avatarLibrary, dataDirectory, sourceAvatars, sourcePortraits);
    }

    @Test
    void collectCandidateDirectoriesIgnoresBlankAndMissingPaths(@TempDir Path tempDir) {
        AppProperties props = new AppProperties();
        props.setAvatarLibraryPath("  ");
        props.setDataPath(tempDir.resolve("missing-data").toString());
        props.setSourceLibraryPath(tempDir.resolve("missing-source").toString());

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, null, null);

        assertThat(resolver.collectCandidateDirectories()).isEmpty();

        props.setDataPath(" ");
        props.setSourceLibraryPath("");
        assertThat(resolver.collectCandidateDirectories()).isEmpty();
    }

    @Test
    void indexDirectoryIgnoresMissingRootsAndNonImageFiles(@TempDir Path tempDir) throws Exception {
        Path avatarDirectory = Files.createDirectory(tempDir.resolve("avatars"));
        Files.write(avatarDirectory.resolve("notes.txt"), new byte[]{1});
        Files.write(avatarDirectory.resolve("no-extension"), new byte[]{2});
        Files.write(avatarDirectory.resolve(".jpg"), new byte[]{3});
        Files.write(avatarDirectory.resolve(" .jpg"), new byte[]{4});

        AppProperties props = new AppProperties();
        LocalAvatarResolver resolver = new LocalAvatarResolver(props, null, null);
        var index = new java.util.HashMap<String, Path>();

        resolver.indexDirectory(null, index);
        resolver.indexDirectory(tempDir.resolve("missing-avatars"), index);
        resolver.indexDirectory(avatarDirectory, index);

        assertThat(index).isEmpty();
    }

    @Test
    void indexDirectoryKeepsTheRawKeyWhenBracketCleanupIsEmpty(@TempDir Path tempDir) throws Exception {
        Path avatarDirectory = Files.createDirectory(tempDir.resolve("avatars"));
        Path image = avatarDirectory.resolve("[].jpg");
        Files.write(image, new byte[]{1, 2, 3});

        AppProperties props = new AppProperties();
        LocalAvatarResolver resolver = new LocalAvatarResolver(props, null, null);
        var index = new java.util.HashMap<String, Path>();

        resolver.indexDirectory(avatarDirectory, index);

        assertThat(index).containsOnlyKeys("[]");
        assertThat(index).containsEntry("[]", image);
    }

    @Test
    void resolveAllCandidatesFallsBackToDisplayNameAndNormalizesImportedImage(@TempDir Path tempDir)
            throws Exception {
        Path avatarsDir = Files.createDirectory(tempDir.resolve("avatars"));
        Files.write(avatarsDir.resolve("王力宏.png"), new byte[]{1, 2, 3});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        byte[] normalizedImage = new byte[]{9, 8, 7};
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        when(normalizer.normalize(any(byte[].class))).thenReturn(normalizedImage);

        AssetWriter assetWriter = mock(AssetWriter.class);
        when(assetWriter.writeArtistCover(eq("wanglihong-custom"), any(byte[].class), eq("jpg")))
                .thenReturn("artist-covers/wanglihong-custom.jpg");

        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("wanglihong-custom", "王力宏", "PERSON", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);
        resolver.setCoverImageNormalizer(normalizer);

        assertThat(resolver.resolveAllCandidates()).isEqualTo(1);

        verify(normalizer).normalize(any(byte[].class));
        verify(assetWriter).writeArtistCover(eq("wanglihong-custom"), same(normalizedImage), eq("jpg"));
        verify(profileService).setAvatarReady(
                "wanglihong-custom", "LOCAL_PACK", "artist-covers/wanglihong-custom.jpg");
    }

    @Test
    void resolveAllCandidatesSkipsNullProfilesAndProfilesWithoutArtistKeys(@TempDir Path tempDir)
            throws Exception {
        Path avatarsDir = Files.createDirectory(tempDir.resolve("avatars"));
        Files.write(avatarsDir.resolve("张学友.jpg"), new byte[]{1, 2, 3});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        AssetWriter assetWriter = mock(AssetWriter.class);
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(Arrays.asList(
                null,
                new ArtistProfileService.Profile(null, "张学友", "PERSON", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);

        assertThat(resolver.resolveAllCandidates()).isZero();

        verify(assetWriter, never()).writeArtistCover(any(), any(), any());
        verify(profileService, never()).setAvatarReady(any(), any(), any());
    }

    @Test
    void resolveAllCandidatesDoesNotStartASecondConcurrentScan(@TempDir Path tempDir) throws Exception {
        Path avatarsDir = Files.createDirectory(tempDir.resolve("avatars"));
        Files.write(avatarsDir.resolve("张学友.jpg"), new byte[]{1, 2, 3});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        CountDownLatch scanEntered = new CountDownLatch(1);
        CountDownLatch continueScan = new CountDownLatch(1);
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenAnswer(invocation -> {
            scanEntered.countDown();
            if (!continueScan.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the first scan");
            }
            return List.of();
        });

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, mock(AssetWriter.class), profileService);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstScan = executor.submit(resolver::resolveAllCandidates);
            assertThat(scanEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(resolver.resolveAllCandidates()).isZero();

            continueScan.countDown();
            assertThat(firstScan.get(5, TimeUnit.SECONDS)).isZero();
            verify(profileService, times(1)).unresolvedProfiles(50_000);
        } finally {
            continueScan.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void resolveAllCandidatesContainsAvatarWriteFailureAndLeavesProfileUnchanged(@TempDir Path tempDir)
            throws Exception {
        Path avatarsDir = Files.createDirectory(tempDir.resolve("avatars"));
        Files.write(avatarsDir.resolve("张学友.jpg"), new byte[]{1, 2, 3});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        AssetWriter assetWriter = mock(AssetWriter.class);
        when(assetWriter.writeArtistCover(any(), any(byte[].class), any()))
                .thenThrow(new IllegalStateException("synthetic asset store failure"));
        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("zhangxueyou", "张学友", "PERSON", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);

        assertThat(resolver.resolveAllCandidates()).isZero();
        verify(profileService, never()).setAvatarReady(any(), any(), any());
    }

    @Test
    void extractLeadArtistReturnsFirstValidNonPlaceholderArtist() {
        assertThat(LocalAvatarResolver.extractLeadArtist("黄诗扶_妖扬")).isEqualTo("黄诗扶");
        assertThat(LocalAvatarResolver.extractLeadArtist("蔡依林 陶喆")).isEqualTo("蔡依林");
        assertThat(LocalAvatarResolver.extractLeadArtist("王力宏 & 陶喆")).isEqualTo("王力宏");
        assertThat(LocalAvatarResolver.extractLeadArtist("林二汶、岑宁儿")).isEqualTo("林二汶");
        assertThat(LocalAvatarResolver.extractLeadArtist("周杰伦")).isEqualTo("周杰伦");
        assertThat(LocalAvatarResolver.extractLeadArtist("群星")).isEmpty();
        assertThat(LocalAvatarResolver.extractLeadArtist("")).isEmpty();
        assertThat(LocalAvatarResolver.extractLeadArtist(null)).isEmpty();
    }

    @Test
    void inheritsLeadArtistAvatarWhenDuetAvatarIsMissing(@TempDir Path tempDir) throws Exception {
        Path avatarsDir = Files.createDirectory(tempDir.resolve("avatars"));
        // 仅有第一主唱黄诗扶的头像，没有合唱黄诗扶_妖扬的单独头像
        Files.write(avatarsDir.resolve("黄诗扶.jpg"), new byte[]{10, 20, 30});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        AssetWriter assetWriter = mock(AssetWriter.class);
        when(assetWriter.writeArtistCover(eq("huangshifu_yaoyang"), any(), eq("jpg")))
                .thenReturn("artist-covers/huangshifu_yaoyang.jpg");

        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("huangshifu_yaoyang", "黄诗扶_妖扬", "GROUP", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);
        int matched = resolver.resolveAllCandidates();

        assertThat(matched).isEqualTo(1);
        verify(assetWriter).writeArtistCover(eq("huangshifu_yaoyang"), any(), eq("jpg"));
        verify(profileService).setAvatarReady("huangshifu_yaoyang", "LOCAL_PACK", "artist-covers/huangshifu_yaoyang.jpg");
    }

    @Test
    void prioritizesFullDuetNameOverLeadArtistAvatarWhenBothExist(@TempDir Path tempDir) throws Exception {
        Path avatarsDir = Files.createDirectory(tempDir.resolve("avatars"));
        Files.write(avatarsDir.resolve("黄诗扶_妖扬.jpg"), new byte[]{1, 1, 1});
        Files.write(avatarsDir.resolve("黄诗扶.jpg"), new byte[]{2, 2, 2});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        AssetWriter assetWriter = mock(AssetWriter.class);
        when(assetWriter.writeArtistCover(eq("huangshifu_yaoyang"), eq(new byte[]{1, 1, 1}), eq("jpg")))
                .thenReturn("artist-covers/full_duet.jpg");

        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("huangshifu_yaoyang", "黄诗扶_妖扬", "GROUP", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);
        int matched = resolver.resolveAllCandidates();

        assertThat(matched).isEqualTo(1);
        verify(assetWriter).writeArtistCover(eq("huangshifu_yaoyang"), eq(new byte[]{1, 1, 1}), eq("jpg"));
        verify(profileService).setAvatarReady("huangshifu_yaoyang", "LOCAL_PACK", "artist-covers/full_duet.jpg");
    }

    @Test
    void reusesNormalizedImageCacheAcrossMultipleProfilesSharingSameAvatar(@TempDir Path tempDir) throws Exception {
        Path avatarsDir = Files.createDirectory(tempDir.resolve("avatars"));
        Files.write(avatarsDir.resolve("周杰伦.jpg"), new byte[]{1, 2, 3});

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        byte[] normalized = new byte[]{9, 9, 9};
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        when(normalizer.normalize(any(byte[].class))).thenReturn(normalized);

        AssetWriter assetWriter = mock(AssetWriter.class);
        when(assetWriter.writeArtistCover(any(), any(), any())).thenReturn("artist-covers/saved.jpg");

        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(List.of(
                new ArtistProfileService.Profile("zhoujielun", "周杰伦", "PERSON", null, null, null, "PENDING"),
                new ArtistProfileService.Profile("zhoujielun_wenlan", "周杰伦_温岚", "GROUP", null, null, null, "PENDING")
        ));

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);
        resolver.setCoverImageNormalizer(normalizer);

        int matched = resolver.resolveAllCandidates();
        assertThat(matched).isEqualTo(2);

        verify(normalizer, times(1)).normalize(any(byte[].class));
        verify(assetWriter).writeArtistCover(eq("zhoujielun"), same(normalized), eq("jpg"));
        verify(assetWriter).writeArtistCover(eq("zhoujielun_wenlan"), same(normalized), eq("jpg"));
        verify(profileService).setAvatarReady("zhoujielun", "LOCAL_PACK", "artist-covers/saved.jpg");
        verify(profileService).setAvatarReady("zhoujielun_wenlan", "LOCAL_PACK", "artist-covers/saved.jpg");
    }

    @Test
    void boundedCoverCacheEvictsLeastRecentlyUsedByEntryCountAndByteBudget(@TempDir Path tempDir) {
        LocalAvatarResolver.BoundedCoverCache entryCache = new LocalAvatarResolver.BoundedCoverCache(2, 1024);
        Path p1 = tempDir.resolve("p1.jpg");
        Path p2 = tempDir.resolve("p2.jpg");
        Path p3 = tempDir.resolve("p3.jpg");

        entryCache.put(p1, new LocalAvatarResolver.ProcessedCover(new byte[]{1, 2}, "jpg"));
        entryCache.put(p2, new LocalAvatarResolver.ProcessedCover(new byte[]{3, 4}, "jpg"));
        assertThat(entryCache.get(p1)).isNotNull();
        entryCache.put(p3, new LocalAvatarResolver.ProcessedCover(new byte[]{5, 6}, "jpg"));

        assertThat(entryCache.size()).isEqualTo(2);
        assertThat(entryCache.totalBytes()).isEqualTo(4L);
        assertThat(entryCache.get(p2)).isNull();
        assertThat(entryCache.get(p1)).isNotNull();
        assertThat(entryCache.get(p3)).isNotNull();

        LocalAvatarResolver.BoundedCoverCache byteCache = new LocalAvatarResolver.BoundedCoverCache(10, 10);
        Path p4 = tempDir.resolve("p4.jpg");
        byteCache.put(p1, new LocalAvatarResolver.ProcessedCover(new byte[4], "jpg"));
        byteCache.put(p2, new LocalAvatarResolver.ProcessedCover(new byte[4], "jpg"));
        byteCache.put(p3, new LocalAvatarResolver.ProcessedCover(new byte[5], "jpg"));

        assertThat(byteCache.size()).isEqualTo(2);
        assertThat(byteCache.totalBytes()).isEqualTo(9L);
        assertThat(byteCache.get(p1)).isNull();
        assertThat(byteCache.get(p2)).isNotNull();
        assertThat(byteCache.get(p3)).isNotNull();

        byteCache.put(p4, new LocalAvatarResolver.ProcessedCover(new byte[11], "jpg"));
        assertThat(byteCache.get(p4)).isNull();
        assertThat(byteCache.totalBytes()).isLessThanOrEqualTo(10L);
    }

    @Test
    void resolveAllCandidatesEvictsCoverCacheBeyondMaxEntries(@TempDir Path tempDir) throws Exception {
        Path avatarsDir = Files.createDirectory(tempDir.resolve("avatars"));
        int distinctArtists = 257;
        java.util.List<ArtistProfileService.Profile> profiles = new java.util.ArrayList<>(distinctArtists + 1);
        for (int i = 0; i < distinctArtists; i++) {
            String name = "歌手" + i;
            Files.write(avatarsDir.resolve(name + ".jpg"), new byte[]{(byte) (i & 0xFF), 2, 3});
            profiles.add(new ArtistProfileService.Profile("p" + i, name, "PERSON", null, null, null, "PENDING"));
        }
        profiles.add(new ArtistProfileService.Profile("p0_dup", "歌手0", "PERSON", null, null, null, "PENDING"));

        AppProperties props = new AppProperties();
        props.setDataPath(tempDir.toString());

        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        when(normalizer.normalize(any(byte[].class))).thenReturn(new byte[]{9, 9, 9});

        AssetWriter assetWriter = mock(AssetWriter.class);
        when(assetWriter.writeArtistCover(any(), any(), any())).thenReturn("artist-covers/saved.jpg");

        ArtistProfileService profileService = mock(ArtistProfileService.class);
        when(profileService.unresolvedProfiles(50_000)).thenReturn(profiles);

        LocalAvatarResolver resolver = new LocalAvatarResolver(props, assetWriter, profileService);
        resolver.setCoverImageNormalizer(normalizer);

        int matched = resolver.resolveAllCandidates();
        assertThat(matched).isEqualTo(distinctArtists + 1);
        verify(normalizer, times(distinctArtists + 1)).normalize(any(byte[].class));
    }
}
