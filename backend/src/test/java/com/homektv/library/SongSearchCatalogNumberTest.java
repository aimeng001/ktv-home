package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Isolated
class SongSearchCatalogNumberTest {

    private SongSearchRepository searchRepo;
    private SongSearchService service;

    @BeforeEach
    void setUp() {
        searchRepo = mock(SongSearchRepository.class);
        service = new SongSearchService(searchRepo);
    }

    @Test
    void searchWithSixDigitNumberPassesNumericIdToLongSearch() {
        when(searchRepo.search(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(List.of(new Song()));

        service.search("046394", 0);

        ArgumentCaptor<Long> numCaptor = ArgumentCaptor.forClass(Long.class);
        verify(searchRepo).search(eq("046394"), eq("046394"), eq("046394"),
                numCaptor.capture(), eq(""), any(Pageable.class));

        assertThat(numCaptor.getValue()).isEqualTo(46394L);
    }

    @Test
    void searchWithShortDigitsPassesNumericIdToShortSearch() {
        when(searchRepo.searchShort(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(List.of(new Song()));

        service.search("46", 0);

        ArgumentCaptor<Long> numCaptor = ArgumentCaptor.forClass(Long.class);
        verify(searchRepo).searchShort(eq("46"), eq("46"), eq("46"),
                numCaptor.capture(), eq(""), any(Pageable.class));

        assertThat(numCaptor.getValue()).isEqualTo(46L);
    }

    @Test
    void searchWithAlphaKeywordPassesZeroNumericIdToPreventSqlCastCrash() {
        when(searchRepo.search(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(List.of(new Song()));

        service.search("zjl", 0);

        ArgumentCaptor<Long> numCaptor = ArgumentCaptor.forClass(Long.class);
        verify(searchRepo).search(eq("zjl"), eq("zjl"), eq("zjl"),
                numCaptor.capture(), eq(""), any(Pageable.class));

        assertThat(numCaptor.getValue()).isEqualTo(0L);
    }

    @Test
    void searchSqlContainsSafeNumericMatchingBranches() {
        assertThat(SongSearchRepository.SEARCH_SQL)
                .contains(":numericId")
                .contains("s.id = :numericId")
                .contains("s.catalog_number = :kw");

        assertThat(SongSearchRepository.SHORT_SEARCH_SQL)
                .contains(":numericId")
                .contains("s.id = :numericId")
                .contains("s.catalog_number = :kw");
    }
}
