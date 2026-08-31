package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistCreditReconciliationServiceTest {

    @Test
    void reconcilesValidSongsInBoundedDatabasePages() {
        List<Song> firstPage = IntStream.rangeClosed(1, 501)
                .mapToObj(id -> song((long) id, "歌手" + id))
                .toList();
        Song second = song(501L, "单依纯/王子异");
        SongRepository songs = mock(SongRepository.class);
        ArtistCreditService credits = mock(ArtistCreditService.class);
        when(songs.findMaxIdByStatus("ok")).thenReturn(501L);
        when(songs.findValidSongsAfterId("ok", 0L, 501L, 501))
                .thenReturn(firstPage);
        when(songs.findValidSongsAfterId("ok", 500L, 501L, 501))
                .thenReturn(List.of(second));

        int processed = new ArtistCreditReconciliationService(songs, credits)
                .reconcileValidSongs();

        assertThat(processed).isEqualTo(501);
        verify(credits).replace(500L, "歌手500");
        verify(credits).replace(501L, "单依纯/王子异");
        verify(songs).findValidSongsAfterId("ok", 0L, 501L, 501);
        verify(songs).findValidSongsAfterId("ok", 500L, 501L, 501);
    }

    @Test
    void skipsRowsWithoutAnIdButClearsStaleAssociationsForBlankCredits() {
        Song missingId = song(null, "歌手");
        Song blankArtist = song(13L, " ");
        SongRepository songs = mock(SongRepository.class);
        ArtistCreditService credits = mock(ArtistCreditService.class);
        when(songs.findMaxIdByStatus("ok")).thenReturn(13L);
        when(songs.findValidSongsAfterId("ok", 0L, 13L, 501))
                .thenReturn(List.of(missingId, blankArtist));

        int processed = new ArtistCreditReconciliationService(songs, credits)
                .reconcileValidSongs();

        assertThat(processed).isEqualTo(1);
        verify(credits).replace(13L, " ");
        verify(credits, times(1)).replace(any(Long.class), any(String.class));
    }

    private static Song song(Long id, String artist) {
        Song song = new Song();
        song.setId(id);
        song.setArtist(artist);
        song.setStatus("ok");
        return song;
    }
}
