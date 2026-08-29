package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class AdminServicePathSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void managedDeleteRejectsSongFileOutsideManagedRootWithoutTouchingIt() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed"));
        Path externalRoot = Files.createDirectory(tempDir.resolve("external"));
        Path source = externalRoot.resolve("source.mkv");
        byte[] originalBytes = new byte[]{0x10, 0x20, 0x30};
        Files.write(source, originalBytes);

        SongRepository songs = mock(SongRepository.class);
        SongFileRepository files = mock(SongFileRepository.class);
        QueueItemRepository queue = mock(QueueItemRepository.class);
        PlayHistoryRepository history = mock(PlayHistoryRepository.class);
        PlayerStateRepository player = mock(PlayerStateRepository.class);
        Song song = new Song();
        song.setId(7L);
        SongFile file = new SongFile();
        file.setSongId(7L);
        file.setFilePath(source.toString());
        when(songs.findById(7L)).thenReturn(Optional.of(song));
        when(files.findBySongIdOrderByPriorityDesc(7L)).thenReturn(List.of(file));

        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());
        AdminService service = new AdminService(songs, files, history, mock(WsBroadcaster.class),
                mock(AssetWriter.class), queue, player, props);

        assertThatThrownBy(() -> service.deleteSong(7L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_LIBRARY_PATH");

        assertThat(Files.readAllBytes(source)).containsExactly(originalBytes);
        verify(songs).findById(7L);
        verify(files).findBySongIdOrderByPriorityDesc(7L);
        verifyNoMoreInteractions(songs, files, queue, history, player);
    }

    @Test
    void springManagedDeleteUsesJournalBeforeRemovingTheDatabaseRecord() {
        SongRepository songs = mock(SongRepository.class);
        SongFileRepository files = mock(SongFileRepository.class);
        QueueItemRepository queue = mock(QueueItemRepository.class);
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        ManagedLibraryDeleteService deleteService = mock(ManagedLibraryDeleteService.class);
        Song song = new Song();
        song.setId(7L);
        SongFile file = new SongFile();
        file.setSongId(7L);
        file.setFilePath("C:\\managed\\song.mkv");
        when(songs.findById(7L)).thenReturn(Optional.of(song));
        when(files.findBySongIdOrderByPriorityDesc(7L)).thenReturn(List.of(file));
        when(deleteService.prepare(7L, List.of(file))).thenReturn("op-7");
        when(playerRepository.getSingletonForUpdate()).thenReturn(new com.homektv.domain.PlayerState());

        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.MANAGED);
        AdminService service = new AdminService(songs, files, mock(PlayHistoryRepository.class),
                mock(WsBroadcaster.class), mock(AssetWriter.class), queue, playerRepository, props);
        service.setManagedLibraryDeleteService(deleteService);

        service.deleteSong(7L);

        verify(queue).lockQueueMutation();
        verify(deleteService).prepare(7L, List.of(file));
        verify(deleteService).stage("op-7");
        verify(deleteService).registerCompletion("op-7");
        verify(songs).delete(song);
        verify(deleteService, never()).complete("op-7");
    }
}
