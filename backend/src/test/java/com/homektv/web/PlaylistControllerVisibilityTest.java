package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.Playlist;
import com.homektv.library.PlaylistPublicService;
import com.homektv.repo.PlaylistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaylistControllerVisibilityTest {

    @TempDir
    Path dataRoot;

    @Test
    void privatePlaylistCoverIsNotPubliclyReadable() throws Exception {
        Files.writeString(dataRoot.resolve("private.jpg"), "cover");
        Playlist privatePlaylist = mock(Playlist.class);
        when(privatePlaylist.getCoverPath()).thenReturn("private.jpg");
        when(privatePlaylist.isPublicVisible()).thenReturn(false);
        PlaylistRepository repository = mock(PlaylistRepository.class);
        when(repository.findById(7L)).thenReturn(Optional.of(privatePlaylist));
        AppProperties properties = new AppProperties();
        properties.setDataPath(dataRoot.toString());

        PlaylistController controller = new PlaylistController(
                mock(PlaylistPublicService.class), repository, properties);

        assertThat(controller.cover(7L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
