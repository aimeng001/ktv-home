package com.homektv.web;

import com.homektv.domain.Song;
import com.homektv.library.CategoryBrowseService;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CategoryBrowseControllerTest {

    @Test
    void songPageExposesTheBoundedPublicSongResponse() {
        CategoryBrowseService service = mock(CategoryBrowseService.class);
        Song song = new Song();
        song.setId(7L);
        song.setTitle("分页歌曲");
        song.setArtist("歌手");
        SongDto dto = SongDto.from(song);
        when(service.songsPage("歌手", "歌手key", "男歌手", "国语", "流行", "独唱", "title", 2, 50))
                .thenReturn(new CategoryBrowseService.SongPage(List.of(dto), 101, 2, 50));

        CategoryBrowseService.SongPage result = new CategoryBrowseController(service)
                .songPage("歌手", "歌手key", "男歌手", "国语", "流行", "独唱", "title", 2, 50);

        assertThat(result.items()).containsExactly(dto);
        assertThat(result.total()).isEqualTo(101);
        assertThat(result.page()).isEqualTo(2);
    }
}
