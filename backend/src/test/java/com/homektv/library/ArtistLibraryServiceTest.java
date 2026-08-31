package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.ai.AiConfigService;
import com.homektv.ai.OpenAiCompatibleClient;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistLibraryServiceTest {
    private SongRepository songs;
    private AiConfigService aiConfig;
    private ArtistLibraryService service;

    @BeforeEach
    void setUp() {
        songs = mock(SongRepository.class);
        aiConfig = mock(AiConfigService.class);
        service = new ArtistLibraryService(songs, aiConfig, mock(OpenAiCompatibleClient.class), new ObjectMapper());
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
