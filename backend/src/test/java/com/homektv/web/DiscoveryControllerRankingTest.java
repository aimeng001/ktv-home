package com.homektv.web;

import com.homektv.domain.Song;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DiscoveryControllerRankingTest {

    private PlayHistoryRepository historyRepo;
    private SongRepository songRepo;
    private DiscoveryController controller;

    @BeforeEach
    void setUp() {
        historyRepo = mock(PlayHistoryRepository.class);
        songRepo = mock(SongRepository.class);
        controller = new DiscoveryController(historyRepo, songRepo);
    }

    @Test
    void ranking_loadsSongsInSingleBatchWithoutNPlusOneQueries() {
        List<Object[]> rows = List.of(
                new Object[]{101L, 10L},
                new Object[]{102L, 5L}
        );
        when(historyRepo.ranking(any(OffsetDateTime.class), eq(20))).thenReturn(rows);

        Song s1 = new Song();
        s1.setId(101L);
        s1.setTitle("Song 101");
        s1.setArtist("Artist 1");
        s1.setStatus("ok");

        Song s2 = new Song();
        s2.setId(102L);
        s2.setTitle("Song 102");
        s2.setArtist("Artist 2");
        s2.setStatus("ok");

        when(songRepo.findAllById(List.of(101L, 102L))).thenReturn(List.of(s2, s1));

        List<SongDto> ranking = controller.ranking(30);

        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).id()).isEqualTo(101L);
        assertThat(ranking.get(1).id()).isEqualTo(102L);

        verify(songRepo, times(1)).findAllById(List.of(101L, 102L));
        verify(songRepo, never()).findById(anyLong());
    }

    @Test
    void ranking_clampsDaysToSafeRangeBetweenOneAnd3650() {
        when(historyRepo.ranking(any(OffsetDateTime.class), eq(20))).thenReturn(List.of());

        OffsetDateTime beforeNegative = OffsetDateTime.now();
        controller.ranking(-5);
        OffsetDateTime afterNegative = OffsetDateTime.now();

        controller.ranking(0);
        controller.ranking(3650);

        OffsetDateTime beforeHuge = OffsetDateTime.now();
        controller.ranking(99999);
        OffsetDateTime afterHuge = OffsetDateTime.now();

        org.mockito.ArgumentCaptor<OffsetDateTime> captor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(historyRepo, times(4)).ranking(captor.capture(), eq(20));

        List<OffsetDateTime> captured = captor.getAllValues();
        assertThat(captured.get(0)).isBetween(beforeNegative.minusDays(1).minusSeconds(2), afterNegative.minusDays(1).plusSeconds(2));
        assertThat(captured.get(1)).isBetween(beforeNegative.minusDays(1).minusSeconds(5), afterHuge.minusDays(1).plusSeconds(2));
        assertThat(captured.get(2)).isBetween(beforeNegative.minusDays(3650).minusSeconds(5), afterHuge.minusDays(3650).plusSeconds(2));
        assertThat(captured.get(3)).isBetween(beforeHuge.minusDays(3650).minusSeconds(2), afterHuge.minusDays(3650).plusSeconds(2));
    }
}
