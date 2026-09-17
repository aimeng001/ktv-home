package com.homektv.ai;

import com.homektv.domain.Playlist;
import com.homektv.domain.PlaylistSong;
import com.homektv.domain.Song;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.PlaylistRepository;
import com.homektv.repo.PlaylistSongRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class AiPlaylistIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ktv").withUsername("ktv").withPassword("ktv");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired AiLibraryService service;
    @Autowired PlaylistRepository playlistRepository;
    @Autowired PlaylistSongRepository playlistSongRepository;
    @Autowired SongRepository songRepository;
    @Autowired AiAnalysisTaskRepository aiAnalysisTaskRepository;

    @Test
    void invalidPreviewDoesNotLeavePlaylistOrAssociations() {
        String suffix = String.valueOf(System.nanoTime());
        Song existing = saveSong("存在的歌曲-" + suffix, "artist-" + suffix, "existing-" + suffix);
        String playlistName = "原子失败-" + suffix;

        assertThatThrownBy(() -> service.savePlaylistFromPreview(
                playlistName, "说明", "AI 策划", true, List.of(existing.getId(), 999_999_999L)))
                .hasMessageContaining("歌曲不存在");

        assertThat(playlistRepository.findByName(playlistName)).isEmpty();
        assertThat(playlistSongRepository.findAll()).noneMatch(item ->
                item.getSongId().equals(existing.getId()));
    }

    @Test
    void validPreviewPersistsDeduplicatedSongsInPreviewOrder() {
        String suffix = String.valueOf(System.nanoTime());
        Song first = saveSong("第一首-" + suffix, "artist-" + suffix, "first-" + suffix);
        Song second = saveSong("第二首-" + suffix, "artist-" + suffix, "second-" + suffix);
        String playlistName = "原子成功-" + suffix;

        Playlist playlist = service.savePlaylistFromPreview(
                playlistName, "说明", "AI 策划", true, List.of(second.getId(), first.getId(), second.getId()));

        assertThat(playlist.getId()).isNotNull();
        assertThat(playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlist.getId()))
                .extracting(PlaylistSong::getSongId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void playlistListReturnsGroupedSongAndManualCounts() {
        String suffix = String.valueOf(System.nanoTime());
        Playlist playlist = new Playlist();
        playlist.setName("摘要歌单-" + suffix);
        playlist.setDescription("摘要测试");
        playlist = playlistRepository.saveAndFlush(playlist);
        Song first = saveSong("摘要一-" + suffix, "artist-" + suffix, "summary-first-" + suffix);
        Song second = saveSong("摘要二-" + suffix, "artist-" + suffix, "summary-second-" + suffix);

        PlaylistSong automatic = new PlaylistSong();
        automatic.setPlaylistId(playlist.getId());
        automatic.setSongId(first.getId());
        automatic.setSortOrder(0);
        automatic.setManual(false);
        PlaylistSong manual = new PlaylistSong();
        manual.setPlaylistId(playlist.getId());
        manual.setSongId(second.getId());
        manual.setSortOrder(1);
        manual.setManual(true);
        playlistSongRepository.saveAll(List.of(automatic, manual));
        playlistSongRepository.flush();

        Long playlistId = playlist.getId();
        Map<String, Object> summary = service.listPlaylists().stream()
                .filter(value -> playlistId.equals(value.get("id")))
                .findFirst()
                .orElseThrow();

        assertThat(summary).containsEntry("songCount", 2L)
                .containsEntry("manualCount", 1L);
    }
    @Test
    void activeSongAnalysisTasksAreUniqueAtTheDatabaseBoundary() {
        String suffix = String.valueOf(System.nanoTime());
        Song song = saveSong("任务幂等-" + suffix, "artist-" + suffix, "task-idempotency-" + suffix);

        aiAnalysisTaskRepository.saveAndFlush(taskFor(song.getId()));

        assertThatThrownBy(() -> aiAnalysisTaskRepository.saveAndFlush(taskFor(song.getId())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private AiAnalysisTask taskFor(Long songId) {
        AiAnalysisTask task = new AiAnalysisTask();
        task.setSongId(songId);
        task.setTargetType("SONG");
        task.setTargetId(songId);
        task.setModelRole("LOCAL");
        task.setModel("LOCAL");
        return task;
    }

    private Song saveSong(String title, String artist, String fingerprint) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setMediaType("KTV_VIDEO");
        song.setStatus("ok");
        song.setFingerprint(fingerprint);
        return songRepository.saveAndFlush(song);
    }
}
