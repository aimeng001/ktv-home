package com.homektv.library;

import com.homektv.common.CatalogCacheEpoch;
import com.homektv.common.SearchKeyword;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongSearchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SongSearchQueryRepositoryTest {

    @Test
    void asciiAcronymQuerySkipsSimilarityAndPushesMediaFilterWithoutOrTrap() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SongRepository songRepo = mock(SongRepository.class);
        SongSearchQueryRepository repo = new SongSearchQueryRepository(jdbc, songRepo);

        Song expected = new Song();
        expected.setId(42L);
        expected.setTitle("一曲相思");
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of(42L));
        when(songRepo.findAllById(List.of(42L))).thenReturn(List.of(expected));

        List<Song> result = repo.search(SearchKeyword.of("YQXS", 50), 0L, "KTV_VIDEO", 0, 50);

        assertThat(result).containsExactly(expected);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .doesNotContain(":mediaType = ''")
                .doesNotContain("similarity(")
                .contains("s.media_type = ?")
                .contains("LOWER(s.title_init) LIKE ?")
                .contains("term.kind = ?");
    }

    @Test
    void cjkGeneralQuerySkipsAsciiPinyinBranchesAndNumericIdBranch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SongRepository songRepo = mock(SongRepository.class);
        SongSearchQueryRepository repo = new SongSearchQueryRepository(jdbc, songRepo);

        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());

        repo.search(SearchKeyword.of("一曲相思", 50), 0L, "", 0, 50);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .doesNotContain("s.title_py LIKE")
                .doesNotContain("s.title_init LIKE")
                .doesNotContain("s.catalog_number = ?")
                .contains("LOWER(s.title) LIKE '%' || ? || '%'")
                .contains("similarity(LOWER(s.title), ?)");
    }

    @Test
    void singleInitialQueryUsesFirstLetterPlayCountTopNBranches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SongRepository songRepo = mock(SongRepository.class);
        SongSearchQueryRepository repo = new SongSearchQueryRepository(jdbc, songRepo);

        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());

        repo.search(SearchKeyword.of("Y", 50), 0L, "", 0, 50);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
        assertThat(sqlCaptor.getValue())
                .contains("LOWER(left(s.title_init, 1)) = ?")
                .contains("LOWER(left(s.artist_init, 1)) = ?")
                .contains("ORDER BY s.play_count DESC, s.id ASC");
    }

    @Test
    @ResourceLock("catalog-cache-epoch")
    void songSearchServiceCachesSameEpochAndInvalidatesOnCatalogEpochBump() {
        SongSearchRepository legacyRepo = mock(SongSearchRepository.class);
        SongSearchQueryRepository dynamicRepo = mock(SongSearchQueryRepository.class);
        CatalogRevisionService revisionService = mock(CatalogRevisionService.class);
        when(revisionService.current()).thenReturn(7L);

        Song s1 = new Song();
        s1.setId(1L);
        s1.setTitle("一曲相思");
        when(dynamicRepo.search(any(), eq(0L), eq(""), eq(0), eq(50))).thenReturn(List.of(s1));

        SongSearchService service = new SongSearchService(legacyRepo, dynamicRepo, revisionService);

        assertThat(service.search("YQXS", 0)).containsExactly(s1);
        assertThat(service.search("YQXS", 0)).containsExactly(s1);
        verify(dynamicRepo, times(1)).search(any(), eq(0L), eq(""), eq(0), eq(50));

        CatalogCacheEpoch.bump();
        assertThat(service.search("YQXS", 0)).containsExactly(s1);
        verify(dynamicRepo, times(2)).search(any(), eq(0L), eq(""), eq(0), eq(50));
    }

    @Test
    void twoCharAsciiQueryUsesV78Prefix2OrderedTopNBranchesWithoutUnorderedLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SongRepository songRepo = mock(SongRepository.class);
        SongSearchQueryRepository repo = new SongSearchQueryRepository(jdbc, songRepo);

        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());

        repo.search(SearchKeyword.of("YQ", 50), 0L, "", 0, 50);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
        assertThat(sqlCaptor.getValue())
                .contains("LOWER(left(s.title_init, 2)) = ?")
                .contains("LOWER(left(s.artist_init, 2)) = ?")
                .contains("ORDER BY s.play_count DESC, s.id ASC LIMIT ?");
    }

    @Test
    void shortCjkQuerySkipsAsciiPinyinBranchesAndUsesFirstCharPlayCountIndex() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SongRepository songRepo = mock(SongRepository.class);
        SongSearchQueryRepository repo = new SongSearchQueryRepository(jdbc, songRepo);

        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());

        repo.search(SearchKeyword.of("一曲", 50), 0L, "", 0, 50);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
        assertThat(sqlCaptor.getValue())
                .doesNotContain("title_init")
                .doesNotContain("title_py")
                .doesNotContain("artist_py")
                .contains("LOWER(left(s.title, 1)) = ?")
                .contains("LOWER(left(s.artist, 1)) = ?")
                .contains("ORDER BY s.play_count DESC, s.id ASC LIMIT ?");
    }
}
