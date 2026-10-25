package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.library.AssetWriter;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.library.SongSearchService;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDetailDto;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SongControllerSecurityTest {

    @TempDir
    Path dataRoot;

    private SongRepository songRepo;
    private SongFileRepository fileRepo;
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
        fileRepo = mock(SongFileRepository.class);
        searchService = mock(SongSearchService.class);
        assetWriter = new AssetWriter(props);
        controller = new SongController(searchService, songRepo, fileRepo, assetWriter);
    }

    @Test
    void coverServesValidCachedImage() throws Exception {
        Files.write(dataRoot.resolve("covers/song1.jpg"), new byte[]{1, 2, 3});
        Song song = song(1L, "covers/song1.jpg", null);
        when(songRepo.findById(1L)).thenReturn(Optional.of(song));

        ResponseEntity<Resource> res = controller.cover(1L);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @ParameterizedTest
    @CsvSource({"png, image/png", "webp, image/webp"})
    void coverUsesImageMediaTypeForSupportedFormats(String extension, String contentType) throws Exception {
        String relativePath = "covers/song." + extension;
        Files.write(dataRoot.resolve(relativePath), new byte[]{1, 2, 3});
        when(songRepo.findById(10L)).thenReturn(Optional.of(song(10L, relativePath, null)));

        ResponseEntity<Resource> response = controller.cover(10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType(contentType));
    }

    @Test
    void coverRejectsTraversalOutsideDataRoot() {
        when(songRepo.findById(2L)).thenReturn(Optional.of(song(2L, "../../parent_secret.txt", null)));

        ResponseEntity<Resource> res = controller.cover(2L);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void coverReturnsNotFoundWhenSongDoesNotExist() {
        when(songRepo.findById(20L)).thenReturn(Optional.empty());

        assertThat(controller.cover(20L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void coverReturnsNotFoundWhenSongHasNoCoverPath() {
        when(songRepo.findById(21L)).thenReturn(Optional.of(song(21L, null, null)));

        assertThat(controller.cover(21L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lyricServesValidLyricFile() throws Exception {
        Files.writeString(dataRoot.resolve("lyrics/song1.lrc"), "[00:01.00]Hello");
        when(songRepo.findById(1L)).thenReturn(Optional.of(song(1L, null, "lyrics/song1.lrc")));

        ResponseEntity<Resource> res = controller.lyric(1L);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    }

    @Test
    void lyricRejectsTraversalOutsideDataRoot() {
        when(songRepo.findById(3L)).thenReturn(Optional.of(song(3L, null, "../../parent_secret.txt")));

        ResponseEntity<Resource> res = controller.lyric(3L);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lyricReturnsNotFoundWhenSongDoesNotExist() {
        when(songRepo.findById(30L)).thenReturn(Optional.empty());

        assertThat(controller.lyric(30L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lyricReturnsNotFoundWhenSongHasNoLyricPath() {
        when(songRepo.findById(31L)).thenReturn(Optional.of(song(31L, null, null)));

        assertThat(controller.lyric(31L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void detailReturnsSongAndAssociatedFilesWhenFound() {
        Song song = song(40L, null, null);
        when(songRepo.findById(40L)).thenReturn(Optional.of(song));
        when(fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(40L)).thenReturn(List.of());

        ResponseEntity<SongDetailDto> response = controller.detail(40L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(40L);
        assertThat(response.getBody().files()).isEmpty();
    }

    @Test
    void detailReturnsNotFoundWithoutLookingUpFilesWhenSongIsMissing() {
        when(songRepo.findById(41L)).thenReturn(Optional.empty());

        assertThat(controller.detail(41L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(fileRepo);
    }

    @Test
    void searchWithoutAvailabilityPolicyKeepsSongsVisibleAndPlayableByDefault() {
        Song song = song(50L, null, null);
        when(searchService.search("", "", 0)).thenReturn(List.of(song));

        List<SongDto> results = controller.search("", "", 0);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo(50L);
            assertThat(result.playable()).isTrue();
            assertThat(result.unavailableReason()).isNull();
        });
    }

    @Test
    void searchWithAvailabilityPolicySkipsReadinessLookupForEmptyResults() {
        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepo);
        controller.setAvailabilityPolicy(policy);
        when(searchService.search("", "", 0)).thenReturn(List.of());

        assertThat(controller.search("", "", 0)).isEmpty();

        verifyNoInteractions(fileRepo);
    }

    @Test
    void searchWithAvailabilityPolicyMarksPlayableAndUnavailableSongs() {
        Song playable = song(60L, null, null);
        Song unavailable = song(61L, null, null);
        List<Song> songs = List.of(playable, unavailable);
        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepo);
        controller.setAvailabilityPolicy(policy);
        when(searchService.search("", "", 0)).thenReturn(songs);
        when(fileRepo.findSongIdsWithReadyFile(Set.of(60L, 61L))).thenReturn(Set.of(60L));

        List<SongDto> results = controller.search("", "", 0);

        assertThat(results).extracting(SongDto::playable).containsExactly(true, false);
        assertThat(results.get(0).unavailableReason()).isNull();
        assertThat(results.get(1).unavailableReason()).isEqualTo(SongAvailabilityPolicy.SONG_NOT_READY);
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

    private static Song song(long id, String coverPath, String lyricPath) {
        Song song = new Song();
        song.setId(id);
        song.setCoverPath(coverPath);
        song.setLyricPath(lyricPath);
        return song;
    }
}
