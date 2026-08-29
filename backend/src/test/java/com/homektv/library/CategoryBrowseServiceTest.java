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
                .containsEntry("songCount", 50);
        verify(repository, never()).findByStatus(any(), any());
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

    private Song song(String gender) {
        Song song = new Song();
        song.setArtistGender(gender);
        return song;
    }
}
