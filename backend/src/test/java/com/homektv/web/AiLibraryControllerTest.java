package com.homektv.web;

import com.homektv.ai.AiLibraryService;
import com.homektv.domain.Playlist;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AiLibraryControllerTest {

    @Test
    void savesPreviewWithOneRequestAndExistingFieldNames() throws Exception {
        AiLibraryService service = mock(AiLibraryService.class);
        Playlist playlist = new Playlist();
        playlist.setName("聚会歌单");
        when(service.savePlaylistFromPreview("聚会歌单", "说明", "AI 策划", true, List.of(1L, 2L)))
                .thenReturn(playlist);
        MockMvc mvc = standaloneSetup(new AiLibraryController(service)).build();

        mvc.perform(post("/api/admin/ai/playlists/from-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"聚会歌单","description":"说明","theme":"AI 策划",
                                 "publicVisible":true,"songIds":[1,2]}
                                """))
                .andExpect(status().isOk());

        verify(service).savePlaylistFromPreview(eq("聚会歌单"), eq("说明"), eq("AI 策划"), eq(true), eq(List.of(1L, 2L)));
    }
}
