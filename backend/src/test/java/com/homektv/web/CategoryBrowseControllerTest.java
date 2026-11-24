package com.homektv.web;

import com.homektv.domain.Song;
import com.homektv.library.CategoryBrowseService;
import com.homektv.contract.SongDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void songPageForwardsOptionalMediaTypeAndKeyword() {
        CategoryBrowseService service = mock(CategoryBrowseService.class);
        when(service.songsPage("", "", "", "", "高清HD歌曲", "", "", "MV", "hot", 0, 18))
                .thenReturn(new CategoryBrowseService.SongPage(List.of(), 0, 0, 18));

        CategoryBrowseService.SongPage result = new CategoryBrowseController(service)
                .songPage("", "", "", "", "高清HD歌曲", "", "", "MV", "hot", 0, 18);

        assertThat(result.size()).isEqualTo(18);
    }

    @Test
    void songPageForwardsOptionalWordCount() {
        CategoryBrowseService service = mock(CategoryBrowseService.class);
        when(service.songsPage("", "", "", "", "", "", "周杰伦", "", 2, "hot", 0, 20))
                .thenReturn(new CategoryBrowseService.SongPage(List.of(), 0, 0, 20));

        CategoryBrowseService.SongPage result = new CategoryBrowseController(service)
                .songPage("", "", "", "", "", "", "周杰伦", "", 2, "hot", 0, 20);

        assertThat(result.size()).isEqualTo(20);
        verify(service).songsPage("", "", "", "", "", "", "周杰伦", "", 2, "hot", 0, 20);
    }
}
