package com.homektv.web;

import com.homektv.common.ApiException;
import com.homektv.library.PlaylistPublicService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaylistControllerTest {

    @Test
    void delegatesPublicPlaylistReadsAndRejectsPublicEdits() {
        PlaylistPublicService service = mock(PlaylistPublicService.class);
        PlaylistController controller = new PlaylistController(service);
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
    void servesResolvedPublicCoversAndReturnsNotFoundWhenAbsent() {
        PlaylistPublicService service = mock(PlaylistPublicService.class);
        PlaylistController controller = new PlaylistController(service);
        when(service.resolvePublicCover(1L)).thenReturn(Optional.empty());
        when(service.resolvePublicCover(4L)).thenReturn(Optional.of(Path.of("singer.PNG")));
        when(service.resolvePublicCover(5L)).thenReturn(Optional.of(Path.of("list.webp")));
        when(service.resolvePublicCover(6L)).thenReturn(Optional.of(Path.of("list.bin")));

        assertThat(controller.cover(1L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.cover(4L).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(controller.cover(5L).getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/webp"));
        assertThat(controller.cover(6L).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
    }
}
