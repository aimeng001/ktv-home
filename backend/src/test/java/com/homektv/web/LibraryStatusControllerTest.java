package com.homektv.web;

import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LibraryStatusControllerTest {
    @Test
    void exposesOnlyThePublicSongCountNeededByTv() throws Exception {
        SongRepository songs = mock(SongRepository.class);
        when(songs.count()).thenReturn(42L);
        MockMvc mvc = standaloneSetup(new LibraryStatusController(songs)).build();

        mvc.perform(get("/api/library/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSongs").value(42))
                .andExpect(jsonPath("$.ktvCount").doesNotExist());
    }
}
