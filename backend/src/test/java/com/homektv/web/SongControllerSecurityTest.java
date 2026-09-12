package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.library.AssetWriter;
import com.homektv.library.SongSearchService;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SongControllerSecurityTest {

    @TempDir
    Path dataRoot;

    private SongRepository songRepo;
    private SongSearchService searchService;
    private AssetWriter assetWriter;
    private SongController controller;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties props = new AppProperties();
        props.setDataPath(dataRoot.toString());
        props.setSourceLibraryPath(dataRoot.resolve("source").toString());
        Files.createDirectories(dataRoot.resolve("covers"));
        Files.createDirectories(dataRoot.resolve("lyrics"));

        songRepo = mock(SongRepository.class);
        SongFileRepository fileRepo = mock(SongFileRepository.class);
        searchService = mock(SongSearchService.class);
        assetWriter = new AssetWriter(props);
        controller = new SongController(searchService, songRepo, fileRepo, assetWriter);
    }

    @Test
    void coverServesValidCachedImage() throws Exception {
        Files.write(dataRoot.resolve("covers/song1.jpg"), new byte[]{1, 2, 3});
        Song song = new Song();
        song.setId(1L);
        song.setCoverPath("covers/song1.jpg");
        when(songRepo.findById(1L)).thenReturn(Optional.of(song));

        ResponseEntity<Resource> res = controller.cover(1L);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void coverRejectsTraversalOutsideDataRoot() {
        Song song = new Song();
        song.setId(2L);
        song.setCoverPath("../../parent_secret.txt");
        when(songRepo.findById(2L)).thenReturn(Optional.of(song));

        ResponseEntity<Resource> res = controller.cover(2L);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lyricServesValidLyricFile() throws Exception {
        Files.writeString(dataRoot.resolve("lyrics/song1.lrc"), "[00:01.00]Hello");
        Song song = new Song();
        song.setId(1L);
        song.setLyricPath("lyrics/song1.lrc");
        when(songRepo.findById(1L)).thenReturn(Optional.of(song));

        ResponseEntity<Resource> res = controller.lyric(1L);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void lyricRejectsTraversalOutsideDataRoot() {
        Song song = new Song();
        song.setId(3L);
        song.setLyricPath("../../parent_secret.txt");
        when(songRepo.findById(3L)).thenReturn(Optional.of(song));

        ResponseEntity<Resource> res = controller.lyric(3L);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void searchPropagatesPageValidationThroughHttp() throws Exception {
        when(searchService.search("x", "", 4001))
                .thenThrow(new ApiException("INVALID_PAGE", "搜索页码超出允许范围"));
        MockMvc mvc = standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/api/songs").param("keyword", "x").param("page", "4001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }
}
