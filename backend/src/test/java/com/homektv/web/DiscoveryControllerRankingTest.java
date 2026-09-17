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
}
