package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryBrowseServiceTest {

    @Test
    void dominantArtistGenderUsesKnownMajority() {
        assertThat(CategoryBrowseService.dominantArtistGender(List.of(
                song("男歌手"), song("男歌手"), song("女歌手"), song("未知"))))
                .isEqualTo("男歌手");
    }

    @Test
    void dominantArtistGenderFallsBackToUnknown() {
        assertThat(CategoryBrowseService.dominantArtistGender(List.of(song("未知"), song(null))))
                .isEqualTo("未知");
        assertThat(CategoryBrowseService.dominantArtistGender(List.of(song(" "), song(""))))
                .isEqualTo("未知");
        assertThat(CategoryBrowseService.dominantArtistGender(List.of())).isEqualTo("未知");
    }

    @Test
    void languages_delegatesToRepositoryAggregationWithoutIteration() {
        SongRepository repository = mock(SongRepository.class);
        SongRepository.LanguageCountProjection p1 = mock(SongRepository.LanguageCountProjection.class);
        when(p1.getLanguage()).thenReturn("国语");
        when(p1.getSongCount()).thenReturn(100L);

        when(repository.aggregateLanguagesByStatus("ok")).thenReturn(List.of(p1));

        List<Map<String, Object>> result = new CategoryBrowseService(repository).languages();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "国语").containsEntry("songCount", 100L);
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    void artists_delegatesToRepositoryAggregationWithoutIteration() {
        SongRepository repository = mock(SongRepository.class);
        SongRepository.ArtistStatsProjection p1 = mock(SongRepository.ArtistStatsProjection.class);
        when(p1.getArtist()).thenReturn("周杰伦");
        when(p1.getArtistGender()).thenReturn("男歌手");
        when(p1.getArtistInit()).thenReturn("Z");
        when(p1.getSongCount()).thenReturn(50L);

        when(repository.aggregateArtistsByStatus("ok")).thenReturn(List.of(p1));

        List<Map<String, Object>> result = new CategoryBrowseService(repository).artists();
        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .containsEntry("name", "周杰伦")
                .containsEntry("initial", "Z")
                .containsEntry("gender", "男歌手")
                .containsEntry("songCount", 50)
                .containsEntry("avatarUrl", null);
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    void legacyArtistEndpointMergesDisplayVariantsByCanonicalArtistKey() {
        SongRepository repository = mock(SongRepository.class);
        SongRepository.ArtistStatsProjection first = mock(SongRepository.ArtistStatsProjection.class);
        SongRepository.ArtistStatsProjection variant = mock(SongRepository.ArtistStatsProjection.class);
        when(first.getArtist()).thenReturn("周杰伦");
        when(first.getArtistKey()).thenReturn("zhoujielun");
        when(first.getArtistGender()).thenReturn("男歌手");
        when(first.getArtistInit()).thenReturn("Z");
        when(first.getSongCount()).thenReturn(5L);
        when(variant.getArtist()).thenReturn("周 杰伦");
        when(variant.getArtistKey()).thenReturn("zhoujielun");
        when(variant.getArtistGender()).thenReturn("男歌手");
        when(variant.getArtistInit()).thenReturn("Z");
        when(variant.getSongCount()).thenReturn(3L);
        when(repository.aggregateArtistsByStatus("ok")).thenReturn(List.of(first, variant));

        List<Map<String, Object>> result = new CategoryBrowseService(repository).artists();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst())
                .containsEntry("artistKey", "zhoujielun")
                .containsEntry("name", "周杰伦")
                .containsEntry("songCount", 8);
    }

    @Test
    void artistsExposesAvatarOnlyWhenTheProfileHasACachedFile() {
        SongRepository repository = mock(SongRepository.class);
        SongRepository.ArtistStatsProjection withAvatar = mock(SongRepository.ArtistStatsProjection.class);
        when(withAvatar.getArtist()).thenReturn("周杰伦");
        when(withAvatar.getArtistKey()).thenReturn("zhoujielun");
        when(withAvatar.getArtistGender()).thenReturn("男歌手");
        when(withAvatar.getArtistInit()).thenReturn("Z");
        when(withAvatar.getSongCount()).thenReturn(1L);
        when(withAvatar.getAvatarPath()).thenReturn("artist-covers/avatar.jpg");
        when(repository.aggregateArtistsByStatus("ok")).thenReturn(List.of(withAvatar));

        Map<String, Object> result = new CategoryBrowseService(repository).artists().getFirst();

        assertThat(result).containsEntry("avatarUrl", "/api/artists/avatar?key=zhoujielun");
    }

    @Test
    void staleProfileCannotTurnAnUnattributedLabelIntoARealArtist() {
        SongRepository repository = mock(SongRepository.class);
        SongRepository.ArtistStatsProjection row = mock(SongRepository.ArtistStatsProjection.class);
        when(row.getArtist()).thenReturn("佚名");
        when(row.getArtistKey()).thenReturn("佚名");
        when(row.getArtistGender()).thenReturn("男歌手");
        when(row.getArtistInit()).thenReturn("");
        when(row.getSongCount()).thenReturn(7285L);
        when(row.getArtistKind()).thenReturn("PERSON");
        when(row.getAvatarPath()).thenReturn("artist-covers/stale.jpg");
        when(repository.aggregateArtistsByStatus("ok")).thenReturn(List.of(row));

        Map<String, Object> result = new CategoryBrowseService(repository).artists().getFirst();

        assertThat(result).containsEntry("artistKind", "UNATTRIBUTED")
                .containsEntry("avatarUrl", null)
                .containsEntry("songCount", 7285);
    }

    @Test
    void legacyArtistAggregationSkipsInvalidRowsAndMergesFallbackMetadata() {
        SongRepository repository = mock(SongRepository.class);
        SongRepository.ArtistStatsProjection blankArtist = artistStatsRow(
                " ", null, "未知", "", 1L, "PERSON", null);
        SongRepository.ArtistStatsProjection missingKey = artistStatsRow(
                "周杰伦", " ", null, null, null, null, null);
        SongRepository.ArtistStatsProjection blankMetadata = artistStatsRow(
                "周杰伦", " ", " ", " Z", 0L, " ", " ");
        SongRepository.ArtistStatsProjection variant = artistStatsRow(
                "周 杰伦", null, "男歌手", "z", 3L, "PERSON", "artist-covers/zhou.jpg");
        SongRepository.ArtistStatsProjection completeMetadata = artistStatsRow(
                "周杰伦", "周杰伦", " ", " ", 0L, "PERSON", "artist-covers/other.jpg");
        SongRepository.ArtistStatsProjection emojiArtist = artistStatsRow(
                "💿", "emoji", null, null, 1L, null, null);
        SongRepository.ArtistStatsProjection musicArtist = artistStatsRow(
                "🎵", "music", null, null, 1L, null, null);
        SongRepository.ArtistStatsProjection noArtist = artistStatsRow(null, null, null, null, null, null, null);
        SongRepository.ArtistStatsProjection emptyKeyArtist = artistStatsRow(
                "no-key", " ", null, null, 1L, null, null);
        when(repository.aggregateArtistsByStatus("ok"))
                .thenReturn(Arrays.asList(null, noArtist, blankArtist, emptyKeyArtist,
                        blankMetadata, missingKey, variant, completeMetadata, emojiArtist, musicArtist));

        try (var keyParser = mockStatic(ArtistCreditParser.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var pinyin = mockStatic(PinyinUtil.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            keyParser.when(() -> ArtistCreditParser.key("no-key")).thenReturn("");
            pinyin.when(() -> PinyinUtil.initials("💿")).thenReturn(null);

            List<Map<String, Object>> result = new CategoryBrowseService(repository).artists();

            assertThat(result).hasSize(3);
            assertThat(result.getFirst())
                    .containsEntry("artistKey", "周杰伦")
                    .containsEntry("name", "周杰伦")
                    .containsEntry("initial", "Z")
                    .containsEntry("gender", "男歌手")
                    .containsEntry("songCount", 3)
                    .containsEntry("artistKind", "PERSON")
                    .containsEntry("avatarUrl", "/api/artists/avatar?key=%E5%91%A8%E6%9D%B0%E4%BC%A6");
            Map<String, Object> unknownPinyin = result.stream()
                    .filter(item -> "💿".equals(item.get("name"))).findFirst().orElseThrow();
            Map<String, Object> blankPinyin = result.stream()
                    .filter(item -> "🎵".equals(item.get("name"))).findFirst().orElseThrow();
            assertThat(unknownPinyin)
                    .containsEntry("name", "💿")
                    .containsEntry("initial", "#")
                    .containsEntry("gender", "未知")
                    .containsEntry("artistKind", "PERSON");
            assertThat(blankPinyin).containsEntry("initial", "#").containsEntry("gender", "未知");
        }
    }

    @Test
    void artistsPageUsesBoundedDatabasePaginationAndMapsInitialAndAvatar() {
        SongRepository repository = mock(SongRepository.class);
        SongRepository.PublicArtistDirectoryProjection row = mock(SongRepository.PublicArtistDirectoryProjection.class);
        when(row.getArtistKey()).thenReturn("zhoujielun");
        when(row.getName()).thenReturn("周杰伦");
        when(row.getInitial()).thenReturn("Z");
        when(row.getGender()).thenReturn("男歌手");
        when(row.getSongCount()).thenReturn(7285L);
        when(row.getArtistKind()).thenReturn("PERSON");
        when(row.getAvatarPath()).thenReturn("artist-covers/zhou.jpg");
        when(repository.pagePublicArtistDirectory(eq("ok"), eq("男歌手"), eq("Z"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 30), 101));

        CategoryBrowseService.ArtistPage result = new CategoryBrowseService(repository)
                .artistsPage("男歌手", "Z", 0, 30);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst())
                .containsEntry("name", "周杰伦")
                .containsEntry("initial", "Z")
                .containsEntry("songCount", 7285)
                .containsEntry("avatarUrl", "/api/artists/avatar?key=zhoujielun");
        assertThat(result.total()).isEqualTo(101);
        verify(repository).pagePublicArtistDirectory(eq("ok"), eq("男歌手"), eq("Z"), any(Pageable.class));
        verify(repository, never()).aggregateArtistsByStatus(any());
    }

    @Test
    void artistPageFallsBackFromEmptyProjectionAndMapsNullFieldsSafely() {
        SongRepository repository = mock(SongRepository.class);
        ArtistDirectoryProjectionService projection = mock(ArtistDirectoryProjectionService.class);
        SongRepository.PublicArtistDirectoryProjection row = mock(SongRepository.PublicArtistDirectoryProjection.class);
        when(row.getArtistKey()).thenReturn(" ");
        when(row.getName()).thenReturn(null);
        when(row.getInitial()).thenReturn(" ");
        when(row.getGender()).thenReturn(null);
        when(row.getSongCount()).thenReturn(Long.MAX_VALUE);
        when(row.getArtistKind()).thenReturn(null);
        when(row.getAvatarPath()).thenReturn("artist-covers/stale.jpg");
        when(projection.page("", "", 0, 30)).thenReturn(Optional.empty());
        when(repository.pagePublicArtistDirectory(eq("ok"), eq(""), eq(""), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Arrays.asList(null, row), PageRequest.of(0, 30), 5));

        CategoryBrowseService.ArtistPage result = new CategoryBrowseService(repository, null, projection)
                .artistsPage(null, " 热门 ", -5, 0);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(30);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst())
                .containsEntry("artistKey", "未知歌手")
                .containsEntry("name", "未知歌手")
                .containsEntry("initial", "W")
                .containsEntry("gender", "未知")
                .containsEntry("songCount", Integer.MAX_VALUE)
                .containsEntry("artistKind", "UNATTRIBUTED")
                .containsEntry("avatarUrl", null);
        verify(repository).pagePublicArtistDirectory(eq("ok"), eq(""), eq(""), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void artistPageHandlesNullRepositoryPageAndNullPageContent() {
        SongRepository repository = mock(SongRepository.class);
        Page<SongRepository.PublicArtistDirectoryProjection> nullContentPage = mock(Page.class);
        when(nullContentPage.getContent()).thenReturn(null);
        when(nullContentPage.getTotalElements()).thenReturn(7L);
        when(repository.pagePublicArtistDirectory(eq("ok"), eq(""), eq(""), any(Pageable.class)))
                .thenReturn(null, nullContentPage);
        CategoryBrowseService service = new CategoryBrowseService(repository);

        CategoryBrowseService.ArtistPage missingPage = service.artistsPage(null, null, 0, 30);
        CategoryBrowseService.ArtistPage nullContent = service.artistsPage(null, null, 0, 30);

        assertThat(missingPage.items()).isEmpty();
        assertThat(missingPage.total()).isZero();
        assertThat(nullContent.items()).isEmpty();
        assertThat(nullContent.total()).isEqualTo(7L);
    }

    @Test
    void publicArtistMappingCoversBlankKeysNamesInitialsKindsAndCounts() {
        SongRepository repository = mock(SongRepository.class);
        List<SongRepository.PublicArtistDirectoryProjection> rows = List.of(
                publicArtistRow("null-name", null, null, null, null, null, null),
                publicArtistRow("blank-name", " ", " ", " ", -5L, " ", null),
                publicArtistRow(null, "💿", " ", "男歌手", 0L, null, null),
                publicArtistRow(null, "张", null, null, null, null, null),
                publicArtistRow("wang", "王歌手", "W", " ", null, " ", " "));
        when(repository.pagePublicArtistDirectory(eq("ok"), eq(""), eq(""), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 30), rows.size()));

        List<Map<String, Object>> items;
        try (var pinyin = mockStatic(PinyinUtil.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            pinyin.when(() -> PinyinUtil.initials("💿")).thenReturn(null);
            items = new CategoryBrowseService(repository).artistsPage(null, null, 0, 30).items();
        }

        assertThat(items).hasSize(5);
        assertThat(items.get(0)).containsEntry("name", "未知歌手").containsEntry("gender", "未知")
                .containsEntry("songCount", 0).containsEntry("artistKind", "UNATTRIBUTED");
        assertThat(items.get(1)).containsEntry("name", "未知歌手").containsEntry("songCount", 0)
                .containsEntry("artistKind", "UNATTRIBUTED");
        assertThat(items.get(2)).containsEntry("artistKey", "💿").containsEntry("initial", "#")
                .containsEntry("gender", "男歌手").containsEntry("artistKind", "PERSON");
        assertThat(items.get(3)).containsEntry("artistKey", "张").containsEntry("initial", "Z")
                .containsEntry("artistKind", "PERSON");
        assertThat(items.get(4)).containsEntry("artistKind", "PERSON").containsEntry("gender", "未知")
                .containsEntry("avatarUrl", null);
    }

    @Test
    void artistInitialsAreLoadedAsASeparateSmallQuery() {
        SongRepository repository = mock(SongRepository.class);
        SongRepository.ArtistInitialProjection z = mock(SongRepository.ArtistInitialProjection.class);
        SongRepository.ArtistInitialProjection a = mock(SongRepository.ArtistInitialProjection.class);
        when(z.getInitial()).thenReturn("Z");
        when(a.getInitial()).thenReturn("A");
        when(repository.findPublicArtistInitials("ok", "")).thenReturn(List.of(a, z));

        assertThat(new CategoryBrowseService(repository).artistInitials(" "))
                .containsExactly("A", "Z");
    }

    @Test
    void artistInitialProjectionAndFallbackFilterNullBlankAndDuplicateValues() {
        SongRepository repository = mock(SongRepository.class);
        ArtistDirectoryProjectionService projection = mock(ArtistDirectoryProjectionService.class);
        SongRepository.ArtistInitialProjection nullInitial = mock(SongRepository.ArtistInitialProjection.class);
        SongRepository.ArtistInitialProjection blankInitial = mock(SongRepository.ArtistInitialProjection.class);
        SongRepository.ArtistInitialProjection lowerInitial = mock(SongRepository.ArtistInitialProjection.class);
        SongRepository.ArtistInitialProjection duplicateInitial = mock(SongRepository.ArtistInitialProjection.class);
        SongRepository.ArtistInitialProjection zInitial = mock(SongRepository.ArtistInitialProjection.class);
        when(nullInitial.getInitial()).thenReturn(null);
        when(blankInitial.getInitial()).thenReturn(" ");
        when(lowerInitial.getInitial()).thenReturn(" a ");
        when(duplicateInitial.getInitial()).thenReturn("A");
        when(zInitial.getInitial()).thenReturn("z");
        when(projection.initials("")).thenReturn(Optional.of(List.of("R")), Optional.empty(), Optional.empty());
        when(repository.findPublicArtistInitials("ok", ""))
                .thenReturn(null, Arrays.asList(null, nullInitial, blankInitial, lowerInitial, duplicateInitial, zInitial));
        CategoryBrowseService service = new CategoryBrowseService(repository, null, projection);

        assertThat(service.artistInitials(null)).containsExactly("R");
        assertThat(service.artistInitials(null)).isEmpty();
        assertThat(service.artistInitials(null)).containsExactly("A", "Z");
        verify(repository, org.mockito.Mockito.times(2)).findPublicArtistInitials("ok", "");
    }

    @Test
    void projectionPageIsUsedBeforeLegacyFullAggregate() {
        SongRepository repository = mock(SongRepository.class);
        ArtistDirectoryProjectionService projection = mock(ArtistDirectoryProjectionService.class);
        when(projection.page("男歌手", "Z", 0, 30)).thenReturn(java.util.Optional.of(
                new ArtistDirectoryProjectionService.ProjectionPage(
                        List.of(new ArtistDirectoryProjectionService.ArtistRow(
                                "zhou", "周杰伦", "Z", "男歌手", 7285L, false, "PERSON", null)),
                        1L)));

        CategoryBrowseService service = new CategoryBrowseService(repository, null, projection);
        CategoryBrowseService.ArtistPage result = service.artistsPage("男歌手", "Z", 0, 30);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items().getFirst()).containsEntry("name", "周杰伦");
        verify(repository, never()).pagePublicArtistDirectory(any(), any(), any(), any());
    }
    @Test
    void tags_delegatesToRepositoryAggregationWithoutIteration() {
        SongRepository repository = mock(SongRepository.class);
        SongRepository.TagCountProjection p1 = mock(SongRepository.TagCountProjection.class);
        when(p1.getName()).thenReturn("流行");
        when(p1.getSongCount()).thenReturn(200L);

        when(repository.aggregateTagsByStatusOk()).thenReturn(List.of(p1));

        List<Map<String, Object>> result = new CategoryBrowseService(repository).tags();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "流行").containsEntry("songCount", 200L);
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    void songs_delegatesToBrowseCategorySongsWithoutIteration() {
        SongRepository repository = mock(SongRepository.class);
        Song s = new Song();
        s.setTitle("晴天");
        s.setArtist("周杰伦");
        s.setStatus("ok");
        when(repository.browseCategorySongs(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(s)));

        List<SongDto> result = new CategoryBrowseService(repository).songs("周杰伦", "", "", "", "", "new", 20);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("晴天");
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    void songsPageUsesBoundedDatabasePageAndReturnsTotal() {
        SongRepository repository = mock(SongRepository.class);
        Song s = new Song();
        s.setId(42L);
        s.setTitle("分页歌曲");
        s.setArtist("周杰伦");
        s.setStatus("ok");
        when(repository.browseCategorySongs(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(s), PageRequest.of(1, 50), 7285));

        CategoryBrowseService.SongPage result = new CategoryBrowseService(repository)
                .songsPage("周杰伦", "", "", "", "", "title", 1, 50);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("分页歌曲");
        assertThat(result.total()).isEqualTo(7285);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(50);
        verify(repository).browseCategorySongs(any(), any(), any(), any(), any(),
                eq(PageRequest.of(1, 50, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.ASC, "title"))));
    }

    @Test
    void songsPageClampsAnUntrustedPageSize() {
        SongRepository repository = mock(SongRepository.class);
        when(repository.browseCategorySongs(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        CategoryBrowseService.SongPage result = new CategoryBrowseService(repository)
                .songsPage("", "", "", "", "", "hot", -1, 10_000);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(100);
        verify(repository).browseCategorySongs(any(), any(), any(), any(), any(),
                eq(PageRequest.of(0, 100, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "play_count")
                        .and(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.ASC, "title"))
                        .and(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.ASC, "id")))));
    }

    @Test
    void songsPageUsesCanonicalArtistKeyWhenDirectoryProvidesOne() {
        SongRepository repository = mock(SongRepository.class);
        Song song = new Song();
        song.setId(43L);
        song.setTitle("变体歌曲");
        song.setArtist("周 杰伦");
        when(repository.browseCategorySongsByArtistKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(song), PageRequest.of(0, 50), 1));

        CategoryBrowseService.SongPage result = new CategoryBrowseService(repository)
                .songsPage("周杰伦", "zhoujielun", "", "", "", "", "hot", 0, 50);

        assertThat(result.items()).extracting(SongDto::title).containsExactly("变体歌曲");
        verify(repository).browseCategorySongsByArtistKey(
                eq("zhoujielun"), eq(""), eq(""), eq(""), eq(""), any(Pageable.class));
                verify(repository, never()).browseCategorySongs(any(), any(), any(), any(), any(), any());
    }

    @Test
    void songsPageUsesOneBatchReadinessQueryForTheWholePage() {
        SongRepository repository = mock(SongRepository.class);
        SongAvailabilityPolicy availability = mock(SongAvailabilityPolicy.class);
        Song song = new Song();
        song.setId(43L);
        song.setTitle("准备中");
        song.setArtist("韩红");
        song.setStatus("ok");
        when(repository.browseCategorySongs(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(song), PageRequest.of(0, 50), 1));
        when(availability.playableSongIds(any())).thenReturn(java.util.Set.of());

        CategoryBrowseService.SongPage result = new CategoryBrowseService(repository, availability)
                .songsPage("", "", "", "", "", "", "hot", 0, 50);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().playable()).isFalse();
        assertThat(result.items().getFirst().unavailableReason()).isEqualTo("SONG_NOT_READY");
        verify(availability).playableSongIds(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void songPagesHandleNullRowsAndUseDefaultSize() {
        SongRepository repository = mock(SongRepository.class);
        Page<Song> nullContentPage = mock(Page.class);
        when(nullContentPage.getContent()).thenReturn(null);
        when(nullContentPage.getTotalElements()).thenReturn(8L);
        when(repository.browseCategorySongs(any(), any(), any(), any(), any(), any()))
                .thenReturn(null, nullContentPage);
        CategoryBrowseService service = new CategoryBrowseService(repository);

        CategoryBrowseService.SongPage missing = service.songsPage(null, null, null, null, null, null, null, -3, 0);
        CategoryBrowseService.SongPage nullContent = service.songsPage("", "", "", "", "", "", "title", 0, 1000);

        assertThat(missing.items()).isEmpty();
        assertThat(missing.total()).isZero();
        assertThat(missing.page()).isZero();
        assertThat(missing.size()).isEqualTo(50);
        assertThat(nullContent.items()).isEmpty();
        assertThat(nullContent.total()).isEqualTo(8L);
        assertThat(nullContent.size()).isEqualTo(100);
    }

    @Test
    void songPageMapsPlayableSongsAndNormalizesLegacyNullFilters() {
        SongRepository repository = mock(SongRepository.class);
        SongAvailabilityPolicy availability = mock(SongAvailabilityPolicy.class);
        Song song = new Song();
        song.setId(99L);
        song.setTitle("可播放");
        song.setArtist("歌手");
        song.setStatus("ok");
        when(repository.browseCategorySongs(eq(""), eq(""), eq(""), eq(""), eq(""), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(song), PageRequest.of(0, 1), 1));
        when(availability.playableSongIds(any())).thenReturn(Set.of(99L));

        List<SongDto> result = new CategoryBrowseService(repository, availability)
                .songs(null, null, null, null, null, "new", 0);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().playable()).isTrue();
        assertThat(result.getFirst().unavailableReason()).isNull();
        verify(repository).browseCategorySongs(eq(""), eq(""), eq(""), eq(""), eq(""),
                eq(PageRequest.of(0, 1, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "created_at")
                        .and(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "id")))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacySongConversionTreatsNullPageContentAsEmpty() {
        SongRepository repository = mock(SongRepository.class);
        Page<Song> page = mock(Page.class);
        when(page.getContent()).thenReturn(null);
        when(repository.browseCategorySongs(any(), any(), any(), any(), any(), any())).thenReturn(page);

        List<SongDto> result = new CategoryBrowseService(repository)
                .songs(null, null, null, null, null, null, 10);

        assertThat(result).isEmpty();
        verify(repository).browseCategorySongs(eq(""), eq(""), eq(""), eq(""), eq(""), any(Pageable.class));
    }

    private SongRepository.ArtistStatsProjection artistStatsRow(String artist, String key, String gender,
                                                                 String initial, Long count, String kind, String avatar) {
        SongRepository.ArtistStatsProjection row = mock(SongRepository.ArtistStatsProjection.class);
        when(row.getArtist()).thenReturn(artist);
        when(row.getArtistKey()).thenReturn(key);
        when(row.getArtistGender()).thenReturn(gender);
        when(row.getArtistInit()).thenReturn(initial);
        when(row.getSongCount()).thenReturn(count);
        when(row.getArtistKind()).thenReturn(kind);
        when(row.getAvatarPath()).thenReturn(avatar);
        return row;
    }

    private SongRepository.PublicArtistDirectoryProjection publicArtistRow(String key, String name,
                                                                            String initial, String gender,
                                                                            Long count, String kind, String avatar) {
        SongRepository.PublicArtistDirectoryProjection row = mock(SongRepository.PublicArtistDirectoryProjection.class);
        when(row.getArtistKey()).thenReturn(key);
        when(row.getName()).thenReturn(name);
        when(row.getInitial()).thenReturn(initial);
        when(row.getGender()).thenReturn(gender);
        when(row.getSongCount()).thenReturn(count);
        when(row.getArtistKind()).thenReturn(kind);
        when(row.getAvatarPath()).thenReturn(avatar);
        return row;
    }

    private Song song(String gender) {
        Song song = new Song();
        song.setArtistGender(gender);
        return song;
    }
}
