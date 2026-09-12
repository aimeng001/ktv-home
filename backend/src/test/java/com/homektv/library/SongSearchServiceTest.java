package com.homektv.library;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homektv.domain.Song;
import com.homektv.repo.SongSearchRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(searchRepo.search(any(), any(), any(), any(), any())).thenReturn(List.of(new Song()));

        service.search(longKeyword, 0);

        ArgumentCaptor<String> kwCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchRepo).search(kwCaptor.capture(), any(String.class), any(String.class), eq(""), any(Pageable.class));

        assertThat(kwCaptor.getValue()).hasSize(50).isEqualTo("a".repeat(50));
    }

    @Test
    void search_trimsAndConvertsToLowercase() {
        when(searchRepo.search(eq("HELLO WORLD"), eq("hello world"), eq("hello world"), eq(""), any())).thenReturn(List.of(new Song()));

        service.search("  HELLO WORLD  ", 0);

        verify(searchRepo).search(eq("HELLO WORLD"), eq("hello world"), eq("hello world"), eq(""), any(Pageable.class));
    }

    @Test
    void searchRoutesOneOrTwoCharacterKeywordsToIndexedTermQuery() {
        when(searchRepo.searchShort(eq("天"), eq("天"), eq("天"), eq(""), any()))
                .thenReturn(List.of(new Song()));

        service.search("天", 0);

        verify(searchRepo).searchShort(eq("天"), eq("天"), eq("天"), eq(""), any(Pageable.class));
        verify(searchRepo, never()).search(any(), any(), any(), any(), any());
    }

    @Test
    void searchPassesMediaTypeToTheDatabaseBeforePaging() {
        when(searchRepo.search(eq("zhou"), eq("zhou"), eq("zhou"), eq("KTV_VIDEO"), any())).thenReturn(List.of(new Song()));

        service.search("zhou", "KTV_VIDEO", 2);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(searchRepo).search(eq("zhou"), eq("zhou"), eq("zhou"), eq("KTV_VIDEO"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void searchRejectsNegativePage() {
        assertThatThrownBy(() -> service.search("zhou", -5))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("INVALID_PAGE");

        verifyNoInteractions(searchRepo);
    }

    @Test
    void searchRejectsPageBeyondSafetyLimit() {
        assertThatThrownBy(() -> service.search("zhou", 4001))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("INVALID_PAGE");

        verifyNoInteractions(searchRepo);
    }

    @Test
    void searchRejectsIntegerMaxPageBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.search("zhou", Integer.MAX_VALUE))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("INVALID_PAGE");

        verifyNoInteractions(searchRepo);
    }

    @Test
    void slowSearchLogContainsDiagnosticsWithoutRawKeyword() {
        String secretKeyword = "机密关键词";
        when(searchRepo.search(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(550L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });

        Logger logger = (Logger) LoggerFactory.getLogger(SongSearchService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.search(secretKeyword, "KTV_VIDEO", 2);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("keywordHash", "keywordLength", "durationMs")
                    .doesNotContain(secretKeyword);
        });
    }

    @Test
    void repositoryFailureIsNotConvertedToAnEmptySearchResult() {
        when(searchRepo.search(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.search("database", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }
}
