package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SongSearchServiceTest {

    private SongSearchRepository searchRepo;
    private SongSearchService service;

    @BeforeEach
    void setUp() {
        searchRepo = mock(SongSearchRepository.class);
        service = new SongSearchService(searchRepo);
    }

    @Test
    void search_returnsEmptyList_whenKeywordIsBlankOrNull() {
        assertThat(service.search(null, 0)).isEmpty();
        assertThat(service.search("", 0)).isEmpty();
        assertThat(service.search("   ", 0)).isEmpty();
        verifyNoInteractions(searchRepo);
    }

    @Test
    void search_truncatesKeywordToMax50Characters() {
        String longKeyword = "a".repeat(100);
        when(searchRepo.search(any(), any(), any())).thenReturn(List.of(new Song()));

        service.search(longKeyword, 0);

        ArgumentCaptor<String> kwCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchRepo).search(kwCaptor.capture(), eq(""), any(Pageable.class));

        assertThat(kwCaptor.getValue()).hasSize(50).isEqualTo("a".repeat(50));
    }

    @Test
    void search_trimsAndConvertsToLowercase() {
        when(searchRepo.search(eq("hello world"), eq(""), any())).thenReturn(List.of(new Song()));

        service.search("  HELLO WORLD  ", 0);

        verify(searchRepo).search(eq("hello world"), eq(""), any(Pageable.class));
    }

    @Test
    void searchPassesMediaTypeToTheDatabaseBeforePaging() {
        when(searchRepo.search(eq("zhou"), eq("KTV_VIDEO"), any())).thenReturn(List.of(new Song()));

        service.search("zhou", "KTV_VIDEO", 2);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(searchRepo).search(eq("zhou"), eq("KTV_VIDEO"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }
}
