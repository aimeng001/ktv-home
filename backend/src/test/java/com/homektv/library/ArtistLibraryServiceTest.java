package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.ai.AiConfigService;
import com.homektv.ai.OpenAiCompatibleClient;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ArtistLibraryServiceTest {
    private SongRepository songs;
    private AiConfigService aiConfig;
    private OpenAiCompatibleClient aiClient;
    private ArtistLibraryService service;

    @BeforeEach
    void setUp() {
        songs = mock(SongRepository.class);
        aiConfig = mock(AiConfigService.class);
        aiClient = mock(OpenAiCompatibleClient.class);
        service = new ArtistLibraryService(songs, aiConfig, aiClient, new ObjectMapper());
    }

    @Test
    void listReturnsDatabaseAggregationAndAtMostFiveRepresentativeSongs() {
        SongRepository.ArtistDirectoryProjection row = directoryRow("同名歌手", "未知", false, 7L);
        List<SongRepository.ArtistSongProjection> samples = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(index -> sampleRow("同名歌手", "歌曲" + index, (long) index))
                .toList();
        when(songs.pageArtistDirectory(eq("ok"), eq("同名"), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 100), 1));
        when(songs.findArtistSamples(eq("ok"), any())).thenReturn(samples);

        List<Map<String, Object>> result = service.list("同名", null, null, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "同名歌手").containsEntry("songCount", 7L);
        assertThat((List<?>) result.get(0).get("songs")).hasSize(5);
        verify(songs, never()).findByStatus(eq("ok"), any(Pageable.class));
    }

    @Test
    void listUsesTheDatabaseKeywordPathWithoutWalkingEverySong() {
        SongRepository.ArtistDirectoryProjection row = directoryRow("边界歌手", "未知", false, 1L);
        when(songs.pageArtistDirectory(eq("ok"), eq("边界歌手"), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 100), 1));
        when(songs.findArtistSamples(eq("ok"), any())).thenReturn(List.of());

        List<Map<String, Object>> result = service.list("边界歌手", null, null, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .containsEntry("name", "边界歌手")
                .containsEntry("songCount", 1L);
        verify(songs, times(1)).pageArtistDirectory(eq("ok"), eq("边界歌手"), eq(""), eq(null), any(Pageable.class));
        verify(songs, never()).findByStatus(eq("ok"), any(Pageable.class));
    }

    @Test
    void usesProfileReviewStateInsteadOfSongMetadataLocks() {
        SongRepository.ArtistDirectoryProjection row = directoryRow("歌手", "女歌手", false, 2L);
        when(songs.pageArtistDirectory(eq("ok"), eq(""), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 100), 1))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 100), 1));
        when(row.getReviewed()).thenReturn(false, true);
        when(songs.findArtistSamples(eq("ok"), any())).thenReturn(List.of());

        assertThat(service.list(null, null, null, 100).get(0)).containsEntry("reviewed", false);
        assertThat(service.list(null, null, null, 100).get(0)).containsEntry("reviewed", true);
        verify(songs, never()).findByStatus(eq("ok"), any(Pageable.class));
    }

    @Test
    void manualReviewChangesArtistProfileWithoutWritingSongGender() {
        Song first = song(1L, "歌手", "歌曲一", "未知", false, 2);
        Song second = song(2L, "歌手", "歌曲二", "未知", false, 1);
        when(songs.countByArtistKeyAndStatus("歌手", "ok")).thenReturn(2L);
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        ReflectionTestUtils.setField(service, "artistProfiles", profiles);

        Map<String, Object> result = service.apply("歌手", "男歌手");

        assertThat(result).containsEntry("updated", 2).containsEntry("gender", "男歌手");
        assertThat(List.of(first, second)).allSatisfy(song -> {
            assertThat(song.getArtistGender()).isEqualTo("未知");
            assertThat(song.isMetadataLocked("artistGender")).isFalse();
        });
        verify(profiles).setGender("歌手", "男歌手");
        verify(songs, never()).saveAll(any());
    }

    @Test
    void manualReviewDoesNotContaminateCollaboratingArtist() {
        Song collaborative = song(1L, "周杰伦_蔡依林", "合唱歌曲", "未知", false, 1);
        when(songs.countByArtistKeyAndStatus("周杰伦", "ok")).thenReturn(1L);
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        ReflectionTestUtils.setField(service, "artistProfiles", profiles);

        service.apply("周杰伦", "男歌手");

        assertThat(collaborative.getArtistGender()).isEqualTo("未知");
        assertThat(collaborative.isMetadataLocked("artistGender")).isFalse();
        verify(profiles).setGender("周杰伦", "男歌手");
        verify(songs, never()).saveAll(any());
    }

    @Test
    void manualReviewAlsoUpdatesTheApplicationOwnedArtistProfile() {
        Song only = song(1L, "歌手", "歌曲", "未知", false, 1);
        when(songs.countByArtistKeyAndStatus("歌手", "ok")).thenReturn(1L);
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        ReflectionTestUtils.setField(service, "artistProfiles", profiles);

        service.apply("歌手", "男歌手");

        verify(profiles).setGender("歌手", "男歌手");
    }

    @Test
    void returnsManualFallbackWhenAiIsNotConfigured() {
        Song sample = song(1L, "歌手", "歌曲", "未知", false, 1);
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(eq("歌手"), eq("ok"), any(Pageable.class)))
                .thenReturn(List.of(sample));
        when(aiConfig.isConfigured()).thenReturn(false);

        assertThat(service.analyze("歌手"))
                .containsEntry("gender", "未知")
                .containsEntry("source", "LOCAL")
                .containsEntry("confidence", 0.0);
    }

    @Test
    void batchAnalysisReturnsOneSuggestionPerDistinctArtist() {
        Song first = song(1L, "歌手甲", "歌曲一", "未知", false, 1);
        Song second = song(2L, "歌手乙", "歌曲二", "未知", false, 1);
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(
                eq("歌手甲"), eq("ok"), any(Pageable.class))).thenReturn(List.of(first));
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(
                eq("歌手乙"), eq("ok"), any(Pageable.class))).thenReturn(List.of(second));
        when(aiConfig.isConfigured()).thenReturn(false);

        List<Map<String, Object>> result = service.analyzeBatch(List.of("歌手甲", "歌手甲", "歌手乙"));

        assertThat(result).extracting(item -> item.get("artist")).containsExactly("歌手甲", "歌手乙");
        assertThat(result).allSatisfy(item -> assertThat(item).containsEntry("source", "LOCAL"));
        verify(songs, never()).findByStatus(eq("ok"), any(Pageable.class));
        verify(songs, never()).findAll();
    }

    @Test
    void neverSendsPlaceholderArtistToAiAnalysis() {
        Song placeholder = song(1L, "佚名", "歌曲", "未知", false, 1);
        when(songs.findByArtistIgnoreCaseAndStatus("佚名", "ok")).thenReturn(List.of(placeholder));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.analyze("佚名"))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("占位歌手");
        verify(aiConfig, never()).isConfigured();
    }

    @Test
    void analyzesSongsThroughIndependentArtistAssociations() {
        Song collaborative = song(1L, "单依纯_王子异", "合唱歌曲", "未知", false, 1);
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(eq("王子异"), eq("ok"), any(Pageable.class)))
                .thenReturn(List.of(collaborative));
        when(aiConfig.isConfigured()).thenReturn(false);

        Map<String, Object> result = service.analyze("王子异");

        assertThat(result).containsEntry("source", "LOCAL");
        assertThat((List<?>) result.get("songs")).extracting("title")
                .containsExactly("合唱歌曲");
        verify(songs).findRepresentativeSongsByArtistKeyAndStatus(eq("王子异"), eq("ok"), any(Pageable.class));
    }

    @Test
    void batchAnalysisUsesIndependentArtistAssociationsForCollaborativeSongs() {
        Song collaborative = song(1L, "单依纯_王子异", "合唱歌曲", "未知", false, 1);
        when(songs.findByStatus(eq("ok"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(collaborative)));
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(
                eq("王子异"), eq("ok"), any(Pageable.class)))
                .thenReturn(List.of(collaborative));
        when(aiConfig.isConfigured()).thenReturn(false);

        List<Map<String, Object>> result = service.analyzeBatch(List.of("王子异"));

        assertThat((List<?>) result.getFirst().get("songs")).extracting("title")
                .containsExactly("合唱歌曲");
        verify(songs).findRepresentativeSongsByArtistKeyAndStatus(
                eq("王子异"), eq("ok"), any(Pageable.class));
    }

    @Test
    void pagedDirectoryUsesDatabaseAggregationAndReturnsTotal() {
        SongRepository.ArtistDirectoryProjection row = mock(SongRepository.ArtistDirectoryProjection.class);
        when(row.getArtistKey()).thenReturn("zhoujielun");
        when(row.getName()).thenReturn("周杰伦");
        when(row.getGender()).thenReturn("男歌手");
        when(row.getSongCount()).thenReturn(7285L);
        when(row.getReviewed()).thenReturn(false);
        when(row.getArtistKind()).thenReturn("PERSON");
        when(row.getAvatarPath()).thenReturn(null);
        when(songs.pageArtistDirectory(eq("ok"), eq(""), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), Pageable.ofSize(50), 7286));
        when(songs.findArtistSamples(eq("ok"), any())).thenReturn(List.of());

        ArtistLibraryService.ArtistPage result = service.page(null, null, null, 0, 50);

        assertThat(result.total()).isEqualTo(7286);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst())
                .containsEntry("artistKey", "zhoujielun")
                .containsEntry("name", "周杰伦")
                .containsEntry("songCount", 7285L);
        verify(songs, never()).findByStatus(eq("ok"), any(Pageable.class));
    }

    @Test
    void pagedDirectoryClampsPageSizeAndKeepsStablePageValues() {
        when(songs.pageArtistDirectory(eq("ok"), eq("歌手"), eq("男歌手"), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(new ArrayList<>(), Pageable.ofSize(100), 0));

        ArtistLibraryService.ArtistPage result = service.page("歌手", "男歌手", true, -5, 1000);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(100);
        assertThat(result.items()).isEmpty();
    }

    @Test
    void staleProfileCannotMakeUnattributedDirectoryRowEligibleForAnalysis() {
        SongRepository.ArtistDirectoryProjection row = directoryRow("佚名", "男歌手", false, 7285L);
        when(row.getArtistKey()).thenReturn("佚名");
        when(row.getArtistKind()).thenReturn("PERSON");
        when(row.getAvatarPath()).thenReturn("artist-covers/stale.jpg");
        when(songs.pageArtistDirectory(eq("ok"), eq(""), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), Pageable.ofSize(50), 1));
        when(songs.findArtistSamples(eq("ok"), any())).thenReturn(List.of());

        Map<String, Object> result = service.page(null, null, null, 0, 50).items().getFirst();

        assertThat(result).containsEntry("artistKind", "UNATTRIBUTED")
                .containsEntry("avatarUrl", null);
    }

    @Test
    void compatibilityListUsesDatabasePagesInsteadOfMaterializingAllSongs() {
        SongRepository.ArtistDirectoryProjection row = mock(SongRepository.ArtistDirectoryProjection.class);
        when(row.getArtistKey()).thenReturn("zhoujielun");
        when(row.getName()).thenReturn("周杰伦");
        when(row.getGender()).thenReturn("未知");
        when(row.getSongCount()).thenReturn(7285L);
        when(row.getReviewed()).thenReturn(false);
        when(row.getArtistKind()).thenReturn("PERSON");
        when(row.getAvatarPath()).thenReturn(null);
        when(songs.pageArtistDirectory(eq("ok"), eq(""), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), Pageable.ofSize(100), 1));
        when(songs.findArtistSamples(eq("ok"), any())).thenReturn(List.of());

        List<Map<String, Object>> result = service.listForCompatibility(null, null, null, 500);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsEntry("name", "周杰伦");
        verify(songs, never()).findByStatus(eq("ok"), any(Pageable.class));
    }

    @Test
    void directListAlsoUsesTheBoundedDatabaseDirectoryPath() {
        SongRepository.ArtistDirectoryProjection row = mock(SongRepository.ArtistDirectoryProjection.class);
        when(row.getArtistKey()).thenReturn("zhoujielun");
        when(row.getName()).thenReturn("周杰伦");
        when(row.getGender()).thenReturn("未知");
        when(row.getSongCount()).thenReturn(7285L);
        when(row.getReviewed()).thenReturn(false);
        when(row.getArtistKind()).thenReturn("PERSON");
        when(row.getAvatarPath()).thenReturn(null);
        when(songs.pageArtistDirectory(eq("ok"), eq("周"), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), Pageable.ofSize(100), 1));
        when(songs.findArtistSamples(eq("ok"), any())).thenReturn(List.of());

        List<Map<String, Object>> result = service.list("周", null, null, 100);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsEntry("name", "周杰伦");
        verify(songs, never()).findByStatus(eq("ok"), any(Pageable.class));
    }

    @Test
    void nullDirectoryPageReturnsAnEmptyPageWithNormalizedBounds() {
        when(songs.pageArtistDirectory(eq("ok"), eq("歌手"), eq("男歌手"), eq(true), any(Pageable.class)))
                .thenReturn(null);

        ArtistLibraryService.ArtistPage result = service.page(" 歌手 ", " 男歌手 ", true, -2, 0);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(50);
        verify(songs, never()).findArtistSamples(anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void directoryPageWithNullContentReturnsNoItemsButPreservesItsTotal() {
        Page<SongRepository.ArtistDirectoryProjection> repositoryPage = mock(Page.class);
        when(repositoryPage.getContent()).thenReturn(null);
        when(repositoryPage.getTotalElements()).thenReturn(9L);
        when(songs.pageArtistDirectory(eq("ok"), eq(""), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(repositoryPage);

        ArtistLibraryService.ArtistPage result = service.page(null, null, null, 0, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isEqualTo(9L);
        verify(songs, never()).findArtistSamples(anyString(), any());
    }

    @Test
    void directoryMappingUsesSafeDefaultsFiltersSampleRowsAndEncodesAvatarKey() {
        SongRepository.ArtistDirectoryProjection blankRow = mock(SongRepository.ArtistDirectoryProjection.class);
        when(blankRow.getArtistKey()).thenReturn("  ");
        when(blankRow.getName()).thenReturn(" ");
        when(blankRow.getGender()).thenReturn(null);
        when(blankRow.getReviewed()).thenReturn(null);
        when(blankRow.getSongCount()).thenReturn(null);
        when(blankRow.getArtistKind()).thenReturn(null);
        when(blankRow.getAvatarPath()).thenReturn(null);

        SongRepository.ArtistDirectoryProjection artistRow = mock(SongRepository.ArtistDirectoryProjection.class);
        when(artistRow.getArtistKey()).thenReturn("a b&c");
        when(artistRow.getName()).thenReturn("林歌手");
        when(artistRow.getGender()).thenReturn(" ");
        when(artistRow.getReviewed()).thenReturn(null);
        when(artistRow.getSongCount()).thenReturn(null);
        when(artistRow.getArtistKind()).thenReturn("PERSON");
        when(artistRow.getAvatarPath()).thenReturn("artists/lin.jpg");
        when(songs.pageArtistDirectory(eq("ok"), eq(""), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(blankRow, artistRow), Pageable.ofSize(50), 2));

        SongRepository.ArtistSongProjection missingKeySample = mock(SongRepository.ArtistSongProjection.class);
        when(missingKeySample.getArtistKey()).thenReturn(null);
        SongRepository.ArtistSongProjection withoutCover = sampleRow("林歌手", "无封面", 10L);
        SongRepository.ArtistSongProjection withCover = sampleRow("林歌手", "有封面", 11L);
        when(withoutCover.getArtistKey()).thenReturn("a b&c");
        when(withCover.getArtistKey()).thenReturn("a b&c");
        when(withCover.getCoverPath()).thenReturn("covers/11.jpg");
        when(songs.findArtistSamples(eq("ok"), eq(List.of("a b&c"))))
                .thenReturn(java.util.Arrays.asList(null, missingKeySample, withoutCover, withCover));

        List<Map<String, Object>> result = service.page(null, null, null, 0, 50).items();

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("name", "未知歌手").containsEntry("gender", "未知")
                .containsEntry("reviewed", false).containsEntry("songCount", 0L).containsEntry("avatarUrl", null);
        assertThat(result.get(1)).containsEntry("gender", "未知").containsEntry("reviewed", false)
                .containsEntry("songCount", 0L).containsEntry("avatarUrl", "/api/artists/avatar?key=a+b%26c");
        assertThat((List<Map<String, Object>>) result.get(1).get("songs"))
                .extracting(song -> song.get("coverUrl"))
                .containsExactly("", "/api/cover/11");
        verify(songs).findArtistSamples("ok", List.of("a b&c"));
    }

    @Test
    void directoryMappingHandlesNullNamesAndFallsBackFromMissingOrBlankKinds() {
        SongRepository.ArtistDirectoryProjection nullName = mock(SongRepository.ArtistDirectoryProjection.class);
        when(nullName.getArtistKey()).thenReturn("null-name");
        when(nullName.getName()).thenReturn(null);
        when(nullName.getGender()).thenReturn(null);
        when(nullName.getReviewed()).thenReturn(false);
        when(nullName.getSongCount()).thenReturn(1L);
        when(nullName.getArtistKind()).thenReturn("PERSON");
        when(nullName.getAvatarPath()).thenReturn(null);

        SongRepository.ArtistDirectoryProjection missingKind = mock(SongRepository.ArtistDirectoryProjection.class);
        when(missingKind.getArtistKey()).thenReturn("missing-kind");
        when(missingKind.getName()).thenReturn("张歌手");
        when(missingKind.getGender()).thenReturn("男歌手");
        when(missingKind.getReviewed()).thenReturn(true);
        when(missingKind.getSongCount()).thenReturn(2L);
        when(missingKind.getArtistKind()).thenReturn(null);
        when(missingKind.getAvatarPath()).thenReturn("covers/blank-path.jpg");

        SongRepository.ArtistDirectoryProjection blankKindAndPath = mock(SongRepository.ArtistDirectoryProjection.class);
        when(blankKindAndPath.getArtistKey()).thenReturn("blank-kind");
        when(blankKindAndPath.getName()).thenReturn("李歌手");
        when(blankKindAndPath.getGender()).thenReturn("女歌手");
        when(blankKindAndPath.getReviewed()).thenReturn(false);
        when(blankKindAndPath.getSongCount()).thenReturn(3L);
        when(blankKindAndPath.getArtistKind()).thenReturn(" ");
        when(blankKindAndPath.getAvatarPath()).thenReturn(" ");
        when(songs.pageArtistDirectory(eq("ok"), eq(""), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(nullName, missingKind, blankKindAndPath), Pageable.ofSize(50), 3));
        when(songs.findArtistSamples(eq("ok"), any())).thenReturn(null);

        List<Map<String, Object>> result = service.page(null, null, null, 0, 50).items();

        assertThat(result.get(0)).containsEntry("name", "未知歌手").containsEntry("artistKind", "UNATTRIBUTED");
        assertThat(result.get(1)).containsEntry("artistKind", "PERSON")
                .containsEntry("avatarUrl", "/api/artists/avatar?key=missing-kind");
        assertThat(result.get(2)).containsEntry("artistKind", "PERSON").containsEntry("avatarUrl", null);
    }

    @Test
    void nullArtistSampleQueryResultMapsToAnEmptySongList() {
        SongRepository.ArtistDirectoryProjection row = directoryRow("林歌手", "未知", false, 1L);
        when(songs.pageArtistDirectory(eq("ok"), eq(""), eq(""), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), Pageable.ofSize(50), 1));
        when(songs.findArtistSamples(eq("ok"), any())).thenReturn(null);

        ArtistLibraryService.ArtistPage result = service.page(null, null, null, 0, 50);

        assertThat(result.items()).hasSize(1);
        assertThat((List<?>) result.items().getFirst().get("songs")).isEmpty();
    }

    @Test
    void analyzeFailsClearlyWhenArtistHasNoRepresentativeSongs() {
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(eq("missing"), eq("ok"), any(Pageable.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.analyze("missing"))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("歌手不存在");
        verify(aiConfig, never()).isConfigured();
    }

    @Test
    void analyzeSortsRepresentativeSongsAndCapsTheSamplesAtFive() {
        List<Song> samples = List.of(
                song(1L, "歌手", "最低", "未知", false, 1),
                song(2L, "歌手", "同播放z", "未知", false, 8),
                song(3L, "歌手", "最高", "未知", false, 10),
                song(4L, "歌手", "同播放A", "未知", false, 8),
                song(5L, "歌手", "次高", "未知", false, 9),
                song(6L, "歌手", "第三", "未知", false, 7));
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(eq("歌手"), eq("ok"), any(Pageable.class)))
                .thenReturn(samples);
        when(aiConfig.isConfigured()).thenReturn(false);

        Map<String, Object> result = service.analyze("歌手");

        assertThat((List<Map<String, Object>>) result.get("songs"))
                .extracting(sample -> sample.get("title"))
                .containsExactly("最高", "次高", "同播放A", "同播放z", "第三");
    }

    @Test
    void configuredAiResponseSanitizesUnknownGenderAndClampsConfidence() throws Exception {
        Song sample = song(1L, "歌手", "歌曲", "未知", false, 1);
        sample.setTags(null);
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(eq("歌手"), eq("ok"), any(Pageable.class)))
                .thenReturn(List.of(sample));
        when(aiConfig.isConfigured()).thenReturn(true);
        when(aiClient.completeJsonPromptOnly(eq("BULK"), anyString(), anyString(), eq(700)))
                .thenReturn(new ObjectMapper().readTree("{\"gender\":\"未知类型\",\"confidence\":4}"));

        Map<String, Object> result = service.analyze("歌手");

        assertThat(result).containsEntry("gender", "未知").containsEntry("confidence", 1.0)
                .containsEntry("source", "AI").containsEntry("reason", "请人工确认");
    }

    @Test
    void configuredAiSupportsLegacyGenderFieldAndClampsLowConfidence() throws Exception {
        Song sample = song(1L, "歌手", "歌曲", "未知", false, 1);
        sample.setTags(new String[]{"流行"});
        sample.setCoverPath("covers/1.jpg");
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(eq("歌手"), eq("ok"), any(Pageable.class)))
                .thenReturn(List.of(sample));
        when(aiConfig.isConfigured()).thenReturn(true);
        when(aiClient.completeJsonPromptOnly(eq("BULK"), anyString(), anyString(), eq(700)))
                .thenReturn(new ObjectMapper().readTree("{\"artistGender\":\"女歌手\",\"confidence\":-3}"));

        Map<String, Object> result = service.analyze("歌手");

        assertThat(result).containsEntry("gender", "女歌手").containsEntry("confidence", 0.0);
        List<Map<String, Object>> analyzedSongs = (List<Map<String, Object>>) result.get("songs");
        assertThat(analyzedSongs).hasSize(1);
        assertThat(analyzedSongs.getFirst()).containsEntry("coverUrl", "/api/cover/1");
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).completeJsonPromptOnly(eq("BULK"), anyString(), prompt.capture(), eq(700));
        assertThat(prompt.getValue()).contains("\"tags\":[\"流行\"]");
    }

    @Test
    void aiFailureReturnsManualFallbackAndUsesExceptionTypeForBlankMessage() {
        Song sample = song(1L, "歌手", "歌曲", "未知", false, 1);
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(eq("歌手"), eq("ok"), any(Pageable.class)))
                .thenReturn(List.of(sample));
        when(aiConfig.isConfigured()).thenReturn(true);
        when(aiClient.completeJsonPromptOnly(eq("BULK"), anyString(), anyString(), eq(700)))
                .thenThrow(new IllegalStateException("  "))
                .thenThrow(new IllegalArgumentException());

        Map<String, Object> blankMessage = service.analyze("歌手");
        Map<String, Object> nullMessage = service.analyze("歌手");

        assertThat(blankMessage).containsEntry("gender", "未知").containsEntry("confidence", 0.0)
                .containsEntry("source", "LOCAL");
        assertThat(blankMessage.get("reason").toString()).contains("IllegalStateException");
        assertThat(nullMessage.get("reason").toString()).contains("IllegalArgumentException");
    }

    @Test
    void nullRepositoryResultsAndBlankRepresentativeNamesReturnEmptyOrNotFound() {
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(eq("missing"), eq("ok"), any(Pageable.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> service.analyze("missing"))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("歌手不存在");
        List<?> nullArtistSamples = ReflectionTestUtils.invokeMethod(
                service, "representativeSongsFor", new Object[]{null});
        List<?> blankArtistSamples = ReflectionTestUtils.invokeMethod(
                service, "representativeSongsFor", new Object[]{"  "});

        assertThat(nullArtistSamples).isEmpty();
        assertThat(blankArtistSamples).isEmpty();
        verify(songs, never()).findRepresentativeSongsByArtistKeyAndStatus(
                eq(""), eq("ok"), any(Pageable.class));
    }

    @Test
    void batchItemWithAnEmptyRepresentativeListReturnsNotFoundFallback() {
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
                service, "analyzeBatchItem", "歌手", List.of());

        assertThat(result).containsEntry("gender", "未知").containsEntry("source", "LOCAL");
        assertThat(result.get("reason").toString()).contains("歌手不存在");
        verify(aiConfig, never()).isConfigured();
    }

    @Test
    void batchNullAndBlankInputsReturnWithoutResolvingAiOrQueryingSongs() {
        assertThat(service.analyzeBatch(null)).isEmpty();
        assertThat(service.analyzeBatch(java.util.Arrays.asList(null, " ", "\t"))).isEmpty();

        verifyNoInteractions(songs, aiConfig, aiClient);
    }

    @Test
    void batchReportsPlaceholderAndMissingArtistWithoutCallingAi() {
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(eq("missing"), eq("ok"), any(Pageable.class)))
                .thenReturn(List.of());

        List<Map<String, Object>> result = service.analyzeBatch(List.of("  佚名  ", " missing "));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("reason").toString()).contains("占位歌手");
        assertThat(result.get(1).get("reason").toString()).contains("歌手不存在");
        verify(songs, times(1)).findRepresentativeSongsByArtistKeyAndStatus(
                eq("missing"), eq("ok"), any(Pageable.class));
        verify(aiConfig, never()).isConfigured();
    }

    @Test
    void batchCapsDistinctInputAtFiveHundredArtists() {
        List<String> artists = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> "artist-" + index)
                .toList();

        List<Map<String, Object>> result = service.analyzeBatch(artists);

        assertThat(result).hasSize(500);
        verify(songs, times(500)).findRepresentativeSongsByArtistKeyAndStatus(
                anyString(), eq("ok"), any(Pageable.class));
    }

    @Test
    void batchUsesResolvedConcurrencyAndHandlesEmptyRepositoryResults() {
        AiConfigService.ResolvedConfig config = new AiConfigService.ResolvedConfig(
                false, "", "", "", 30, 0.8, 0.8, AiConfigService.JsonMode.AUTO, 8, 1, "");
        when(aiConfig.resolve()).thenReturn(config);
        when(aiConfig.isConfigured()).thenReturn(false);
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(
                eq("missing"), eq("ok"), any(Pageable.class))).thenReturn(null);
        when(songs.findRepresentativeSongsByArtistKeyAndStatus(
                eq("empty"), eq("ok"), any(Pageable.class))).thenReturn(List.of());

        List<Map<String, Object>> result = service.analyzeBatch(List.of("missing", "empty"));

        assertThat(result).hasSize(2).allSatisfy(item ->
                assertThat(item.get("reason").toString()).contains("歌手不存在"));
        verify(aiConfig).resolve();
    }

    @Test
    void compatibilityListFetchesNextPageWhenCurrentPageIsNotComplete() {
        ArtistLibraryService.ArtistPage first = new ArtistLibraryService.ArtistPage(
                List.of(Map.of("name", "第一位")), 2, 0, 100);
        ArtistLibraryService.ArtistPage second = new ArtistLibraryService.ArtistPage(
                List.of(Map.of("name", "第二位")), 2, 1, 100);
        ArtistLibraryService pagedService = serviceWithPages(first, second);

        List<Map<String, Object>> result = pagedService.listForCompatibility(null, null, null, 2);

        assertThat(result).extracting(item -> item.get("name")).containsExactly("第一位", "第二位");
    }

    @Test
    void compatibilityListStopsAtRequestedLimitWhenMorePagesRemain() {
        ArtistLibraryService.ArtistPage first = new ArtistLibraryService.ArtistPage(
                List.of(Map.of("name", "第一位")), 2, 0, 100);
        ArtistLibraryService pagedService = serviceWithPages(first);

        List<Map<String, Object>> result = pagedService.listForCompatibility(null, null, null, 1);

        assertThat(result).containsExactly(Map.of("name", "第一位"));
    }

    @Test
    void compatibilityListStopsOnNullOrEmptyPageItems() {
        ArtistLibraryService nullItemsService = serviceWithPages(
                new ArtistLibraryService.ArtistPage(null, 4, 0, 100));
        ArtistLibraryService emptyItemsService = serviceWithPages(
                new ArtistLibraryService.ArtistPage(List.of(), 0, 0, 100));

        assertThat(nullItemsService.listForCompatibility(null, null, null, 10)).isEmpty();
        assertThat(emptyItemsService.listForCompatibility(null, null, null, 10)).isEmpty();
    }

    @Test
    void applyRejectsInvalidGenderPlaceholderAndMissingArtist() {
        assertThatThrownBy(() -> service.apply("歌手", "中性"))
                .isInstanceOf(com.homektv.web.ApiException.class).hasMessageContaining("类型无效");
        assertThatThrownBy(() -> service.apply("佚名", "男歌手"))
                .isInstanceOf(com.homektv.web.ApiException.class).hasMessageContaining("占位歌手");
        when(songs.countByArtistKeyAndStatus("不存在", "ok")).thenReturn(0L);
        assertThatThrownBy(() -> service.apply("不存在", "男歌手"))
                .isInstanceOf(com.homektv.web.ApiException.class).hasMessageContaining("歌手不存在");

        verify(songs, times(1)).countByArtistKeyAndStatus("不存在", "ok");
    }

    @Test
    void applyWithoutOptionalProfileClampsVeryLargeUpdateCount() {
        when(songs.countByArtistKeyAndStatus("歌手", "ok")).thenReturn(Long.MAX_VALUE);

        Map<String, Object> result = service.apply("歌手", "女歌手");

        assertThat(result).containsEntry("updated", Integer.MAX_VALUE).containsEntry("gender", "女歌手");
    }

    private SongRepository.ArtistDirectoryProjection directoryRow(String name, String gender,
                                                                     boolean reviewed, long songCount) {
        SongRepository.ArtistDirectoryProjection row = mock(SongRepository.ArtistDirectoryProjection.class);
        when(row.getArtistKey()).thenReturn(ArtistCreditParser.key(name));
        when(row.getName()).thenReturn(name);
        when(row.getGender()).thenReturn(gender);
        when(row.getSongCount()).thenReturn(songCount);
        when(row.getReviewed()).thenReturn(reviewed);
        when(row.getArtistKind()).thenReturn("PERSON");
        when(row.getAvatarPath()).thenReturn(null);
        return row;
    }

    private ArtistLibraryService serviceWithPages(ArtistLibraryService.ArtistPage... pages) {
        return new ArtistLibraryService(songs, aiConfig, aiClient, new ObjectMapper()) {
            private int nextPage;

            @Override
            public ArtistPage page(String keyword, String gender, Boolean reviewed, int page, int size) {
                return pages[Math.min(nextPage++, pages.length - 1)];
            }
        };
    }

    private SongRepository.ArtistSongProjection sampleRow(String artist, String title, long id) {
        SongRepository.ArtistSongProjection row = mock(SongRepository.ArtistSongProjection.class);
        when(row.getArtistKey()).thenReturn(ArtistCreditParser.key(artist));
        when(row.getSongId()).thenReturn(id);
        when(row.getTitle()).thenReturn(title);
        when(row.getArtist()).thenReturn(artist);
        when(row.getLanguage()).thenReturn("国语");
        when(row.getMediaType()).thenReturn("KTV_VIDEO");
        when(row.getCoverPath()).thenReturn(null);
        return row;
    }

    private Song song(Long id, String artist, String title, String gender, boolean locked, int playCount) {
        Song song = new Song();
        song.setId(id);
        song.setArtist(artist);
        song.setTitle(title);
        song.setArtistGender(gender);
        song.setStatus("ok");
        song.setLanguage("国语");
        song.setMediaType("KTV_VIDEO");
        song.setPlayCount(playCount);
        if (locked) song.lockMetadata("artistGender");
        return song;
    }
}
