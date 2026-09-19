package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    private Song song(String gender) {
        Song song = new Song();
        song.setArtistGender(gender);
        return song;
    }
}
