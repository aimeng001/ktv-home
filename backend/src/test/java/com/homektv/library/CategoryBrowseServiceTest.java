package com.homektv.library;

import com.homektv.domain.Song;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import com.homektv.repo.SongRepository;

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
    void readsValidSongsThroughPagedQueryInsteadOfUnboundedFindAll() {
        SongRepository repository = mock(SongRepository.class);
        Song value = song("男歌手");
        value.setStatus("ok");
        value.setLanguage("国语");
        when(repository.findByStatus(eq("ok"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(value)));
        when(repository.findAll()).thenThrow(new AssertionError("unpaged song query is forbidden"));

        List<Map<String, Object>> result = new CategoryBrowseService(repository).languages();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "国语").containsEntry("songCount", 1L);
        verify(repository, never()).findAll();
    }

    private Song song(String gender) {
        Song song = new Song();
        song.setArtistGender(gender);
        return song;
    }
}
