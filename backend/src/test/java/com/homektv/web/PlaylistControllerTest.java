package com.homektv.web;

import com.homektv.domain.Playlist;
import com.homektv.library.AssetWriter;
import com.homektv.library.PlaylistPublicService;
import com.homektv.repo.PlaylistRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaylistControllerTest {

    @Test
    void delegatesPublicPlaylistReadsAndRejectsPublicEdits() {
        PlaylistPublicService service = mock(PlaylistPublicService.class);
        PlaylistRepository repository = mock(PlaylistRepository.class);
        PlaylistController controller = new PlaylistController(service, repository, mock(AssetWriter.class));
        when(service.list()).thenReturn(List.of(Map.of("id", 4L)));
        when(service.detail(4L)).thenReturn(Map.of("id", 4L));
        when(service.orderAll(4L, "client-token")).thenReturn(Map.of("ordered", 1));

        assertThat(controller.list()).containsExactly(Map.of("id", 4L));
        assertThat(controller.detail(4L)).containsEntry("id", 4L);
        assertThat(controller.orderAll(4L, new PlaylistController.OrderPlaylistRequest("client-token")))
                .containsEntry("ordered", 1);
        assertThatThrownBy(() -> controller.addSong(4L, new PlaylistController.AddSongRequest(12L)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("PUBLIC_PLAYLIST_READ_ONLY"));
        verify(service).orderAll(4L, "client-token");
    }

    @Test
    void coverRequiresAVisiblePlaylistAndAnExistingCoverPath() {
        PlaylistRepository repository = mock(PlaylistRepository.class);
        AssetWriter assets = mock(AssetWriter.class);
        PlaylistController controller = new PlaylistController(mock(PlaylistPublicService.class), repository, assets);
        Playlist hidden = playlist(2L, false, "hidden.jpg");
        Playlist noCover = playlist(3L, true, null);
        when(repository.findById(1L)).thenReturn(Optional.empty());
        when(repository.findById(2L)).thenReturn(Optional.of(hidden));
        when(repository.findById(3L)).thenReturn(Optional.of(noCover));

        assertThat(controller.cover(1L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.cover(2L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.cover(3L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(assets, never()).readableCachePath(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void servesPngWebpAndDefaultJpegCoversWithCorrectMediaTypes() {
        PlaylistRepository repository = mock(PlaylistRepository.class);
        AssetWriter assets = mock(AssetWriter.class);
        PlaylistController controller = new PlaylistController(mock(PlaylistPublicService.class), repository, assets);
        when(repository.findById(4L)).thenReturn(Optional.of(playlist(4L, true, "covers/singer.PNG")));
        when(repository.findById(5L)).thenReturn(Optional.of(playlist(5L, true, "covers/list.webp")));
        when(repository.findById(6L)).thenReturn(Optional.of(playlist(6L, true, "covers/list.bin")));
        when(assets.readableCachePath("covers/singer.PNG")).thenReturn(Optional.of(Path.of("singer.PNG")));
        when(assets.readableCachePath("covers/list.webp")).thenReturn(Optional.of(Path.of("list.webp")));
        when(assets.readableCachePath("covers/list.bin")).thenReturn(Optional.of(Path.of("list.bin")));

        assertThat(controller.cover(4L).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(controller.cover(5L).getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/webp"));
        assertThat(controller.cover(6L).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    void returnsNotFoundWhenTheStoredCoverCannotBeRead() {
        PlaylistRepository repository = mock(PlaylistRepository.class);
        AssetWriter assets = mock(AssetWriter.class);
        when(repository.findById(7L)).thenReturn(Optional.of(playlist(7L, true, "covers/missing.jpg")));
        when(assets.readableCachePath("covers/missing.jpg")).thenReturn(Optional.empty());
        PlaylistController controller = new PlaylistController(mock(PlaylistPublicService.class), repository, assets);

        assertThat(controller.cover(7L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static Playlist playlist(long id, boolean visible, String coverPath) {
        Playlist playlist = new Playlist();
        playlist.setId(id);
        playlist.setPublicVisible(visible);
        playlist.setCoverPath(coverPath);
        return playlist;
    }
}
