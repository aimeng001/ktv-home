package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminServiceBatchDeleteTest {

    @Test
    void nullAndEmptyRequestsReturnZeroWithoutTouchingRepositories() {
        var fixture = new Fixture();

        assertThat(fixture.service.deleteSongs(null)).isZero();
        assertThat(fixture.service.deleteSongs(List.of())).isZero();

        verifyNoInteractions(fixture.songs, fixture.files, fixture.history,
                fixture.queue, fixture.player);
    }

    @Test
    void duplicateSongIdsDeleteEachSongAndItsDependentRowsOnlyOnce() {
        var fixture = new Fixture();
        Song first = song(1L);
        Song second = song(2L);
        when(fixture.songs.findById(1L)).thenReturn(Optional.of(first));
        when(fixture.songs.findById(2L)).thenReturn(Optional.of(second));
        when(fixture.files.findBySongIdOrderByPriorityDesc(1L)).thenReturn(List.of());
        when(fixture.files.findBySongIdOrderByPriorityDesc(2L)).thenReturn(List.of());
        when(fixture.queue.findBySongId(1L)).thenReturn(List.of());
        when(fixture.queue.findBySongId(2L)).thenReturn(List.of());
        when(fixture.player.getSingleton()).thenReturn(new com.homektv.domain.PlayerState());

        int deleted = fixture.service.deleteSongs(List.of(1L, 1L, 2L, 2L));

        assertThat(deleted).isEqualTo(2);
        verify(fixture.songs, times(1)).delete(first);
        verify(fixture.songs, times(1)).delete(second);
        verify(fixture.queue, times(2)).deleteAll(List.of());
        verify(fixture.history).deleteBySongId(1L);
        verify(fixture.history).deleteBySongId(2L);
    }

    private static Song song(long id) {
        Song song = new Song();
        song.setId(id);
        return song;
    }

    private static final class Fixture {
        final SongRepository songs = mock(SongRepository.class);
        final SongFileRepository files = mock(SongFileRepository.class);
        final PlayHistoryRepository history = mock(PlayHistoryRepository.class);
        final QueueItemRepository queue = mock(QueueItemRepository.class);
        final PlayerStateRepository player = mock(PlayerStateRepository.class);
        final AdminService service;

        Fixture() {
            AppProperties properties = new AppProperties();
            properties.setLibraryMode(LibraryMode.MANAGED);
            properties.setKtvLibraryPath("C:\\managed");
            service = new AdminService(songs, files, history, mock(WsBroadcaster.class),
                    mock(AssetWriter.class), queue, player, properties);
        }
    }
}
