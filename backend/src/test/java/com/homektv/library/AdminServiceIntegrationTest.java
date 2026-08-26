package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.queue.UserService;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.DashboardDto;
import com.homektv.web.dto.SongEditRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理后台服务集成测试（P2.1-P2.6/P2.13 验收）。
 */
@SpringBootTest
@Testcontainers
class AdminServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ktv").withUsername("ktv").withPassword("ktv");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired AdminService adminService;
    @Autowired SettingService settingService;
    @Autowired UserService userService;
    @Autowired SongRepository songRepo;
    @Autowired com.homektv.repo.SongFileRepository fileRepo;

    @BeforeEach
    void seed() {
        fileRepo.deleteAll();
        songRepo.deleteAll();
        save("晴天", "周杰伦", "KTV_VIDEO", "ok");
        save("海阔天空", "Beyond", "MV", "ok");
        save("后来", "刘若英", "AUDIO", "ok");
        save("TRACK_001", "未知歌手", "AUDIO", "unrecognized");
    }

    private Song save(String title, String artist, String type, String status) {
        Song s = new Song();
        s.setTitle(title); s.setArtist(artist); s.setMediaType(type);
        s.setStatus(status); s.setFingerprint("fp-" + title);
        return songRepo.save(s);
    }

    @Test
    void dashboardCounts() {
        DashboardDto d = adminService.dashboard();
        assertThat(d.totalSongs()).isEqualTo(4);
        assertThat(d.ktvCount()).isEqualTo(1);
        assertThat(d.mvCount()).isEqualTo(1);
        assertThat(d.audioCount()).isEqualTo(2);
        assertThat(d.unrecognizedCount()).isEqualTo(1);
    }

    @Test
    void listFilterByType() {
        assertThat(adminService.listSongs("KTV_VIDEO", 0, 20).getContent()).hasSize(1);
        assertThat(adminService.listSongs("unrecognized", 0, 20).getContent()).hasSize(1);
        assertThat(adminService.listSongs("", 0, 20).getTotalElements()).isEqualTo(4);
    }

    @Test
    void adminSongListUsesDatabasePaginationAndBatchLoadsFiles() {
        Song sunny = songRepo.findByFingerprint("fp-晴天").orElseThrow();
        Song beyond = songRepo.findByFingerprint("fp-海阔天空").orElseThrow();
        Song later = songRepo.findByFingerprint("fp-后来").orElseThrow();
        Song unknown = songRepo.findByFingerprint("fp-TRACK_001").orElseThrow();
        fileRepo.save(adminFile(sunny.getId(), "/music/晴天.mkv", false, "/source-music/晴天.mkv"));
        fileRepo.save(adminFile(beyond.getId(), "/music/海阔天空.mkv", true, "/source-music/海阔天空.mpg"));
        fileRepo.save(adminFile(later.getId(), "/music/后来.mp3", false, "/source-music/后来.mp3"));
        fileRepo.save(adminFile(unknown.getId(), "/music/TRACK_001.mp3", false, null));

        var firstPage = adminService.listAdminSongs("", "", "", 0, 2);
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getContent()).allMatch(song -> song.filePath() != null);
        assertThat(adminService.listAdminSongs("晴天", "", "", 0, 20).getTotalElements()).isEqualTo(1);
        assertThat(adminService.listAdminSongs("", "", "TRANSCODED", 0, 20).getTotalElements()).isEqualTo(1);
        assertThat(adminService.listAdminSongs("", "", "UNKNOWN", 0, 20).getTotalElements()).isEqualTo(1);
    }

    @Test
    void editRecomputesPinyinAndPromotesUnrecognized() {
        Song track = songRepo.findByFingerprint("fp-TRACK_001").orElseThrow();
        adminService.editSong(track.getId(),
                new SongEditRequest("怎么了", "周杰伦", null, null, null));
        Song updated = songRepo.findById(track.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("怎么了");
        assertThat(updated.getArtistInit()).isEqualTo("zjl");   // 重算拼音
        assertThat(updated.getStatus()).isEqualTo("ok");        // 未识别转正
        assertThat(updated.isMetadataLocked("title")).isTrue();
        assertThat(updated.isMetadataLocked("artist")).isTrue();
    }

    @Test
    void editIdentityRecomputesFingerprintFromTheFinalBusinessIdentity() {
        Song track = songRepo.findByFingerprint("fp-TRACK_001").orElseThrow();
        track.setDurationMs(180_000);
        songRepo.save(track);

        adminService.editSong(track.getId(),
                new SongEditRequest("爱", "草蜢", null, null, null));

        Song updated = songRepo.findById(track.getId()).orElseThrow();
        assertThat(updated.getFingerprint())
                .isEqualTo(MediaClassifier.fingerprint("草蜢", "爱", 180_000));
    }

    @Test
    void editIdentityRejectsFingerprintConflict() {
        Song target = songRepo.findByFingerprint("fp-TRACK_001").orElseThrow();
        Song other = save("临时歌名", "临时歌手", "AUDIO", "ok");
        other.setFingerprint(MediaClassifier.fingerprint("草蜢", "爱", target.getDurationMs()));
        songRepo.save(other);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adminService.editSong(target.getId(),
                        new SongEditRequest("爱", "草蜢", null, null, null)))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SONG_FINGERPRINT_CONFLICT");
    }

    @Test
    void editingTagsLocksThemEvenWhenTheRequestedListIsEmpty() {
        Song track = songRepo.findByFingerprint("fp-TRACK_001").orElseThrow();

        adminService.editSong(track.getId(),
                new SongEditRequest(null, null, null, new String[0], null));

        Song updated = songRepo.findById(track.getId()).orElseThrow();
        assertThat(updated.getTags()).isEmpty();
        assertThat(updated.isMetadataLocked("tags")).isTrue();
    }

    @Test
    void languageOnlyEditDoesNotPromoteUnrecognized() {
        Song track = songRepo.findByFingerprint("fp-TRACK_001").orElseThrow();

        adminService.editSong(track.getId(),
                new SongEditRequest(null, null, "粤语", null, null, null, null));

        Song updated = songRepo.findById(track.getId()).orElseThrow();
        assertThat(updated.getLanguage()).isEqualTo("粤语");
        assertThat(updated.isMetadataLocked("language")).isTrue();
        assertThat(updated.getStatus()).isEqualTo("unrecognized");
    }

    @Test
    void vocalFormOnlyEditDoesNotPromoteUnrecognized() {
        Song track = songRepo.findByFingerprint("fp-TRACK_001").orElseThrow();

        adminService.editSong(track.getId(),
                new SongEditRequest(null, null, null, null, null, "DUAL_CHANNEL", null));

        assertThat(songRepo.findById(track.getId()).orElseThrow().getStatus())
                .isEqualTo("unrecognized");
    }

    @Test
    void artistGenderOnlyEditDoesNotPromoteUnrecognized() {
        Song track = songRepo.findByFingerprint("fp-TRACK_001").orElseThrow();

        adminService.editSong(track.getId(),
                new SongEditRequest(null, null, null, null, null, null, "组合"));

        assertThat(songRepo.findById(track.getId()).orElseThrow().getStatus())
                .isEqualTo("unrecognized");
    }

    @Test
    void deleteRemovesRecord() {
        Song s = songRepo.findByFingerprint("fp-后来").orElseThrow();
        adminService.deleteSong(s.getId());
        assertThat(songRepo.findById(s.getId())).isEmpty();
    }

    @Test
    void settingsRoundTrip() {
        settingService.putAll(java.util.Map.of(
                "qr_address", "192.168.1.10:8080",
                "standby_carousel", true));
        var all = settingService.getAll();
        assertThat(all.get("qr_address")).isEqualTo("192.168.1.10:8080");
        assertThat(all.get("standby_carousel")).isEqualTo(true);
    }

    @Test
    void vocalReviewListsOnlyLowConfidence() {
        Song ktv = songRepo.findByFingerprint("fp-晴天").orElseThrow();
        fileRepo.save(vocalFile(ktv.getId(), "/music/晴天.mkv", 2, 1, "LOW"));
        fileRepo.save(vocalFile(ktv.getId(), "/music/晴天.hi.mkv", 2, 1, "HIGH"));

        var review = adminService.listVocalReview(0, 20);
        assertThat(review.getTotalElements()).isEqualTo(1);
        assertThat(review.getContent().get(0).title()).isEqualTo("晴天");
        assertThat(review.getContent().get(0).vocalConfidence()).isEqualTo("LOW");
    }

    @Test
    void confirmVocalTrackSetsIndexAndHigh() {
        Song ktv = songRepo.findByFingerprint("fp-晴天").orElseThrow();
        var file = fileRepo.save(vocalFile(ktv.getId(), "/music/晴天.mkv", 2, 1, "LOW"));

        adminService.confirmVocalTrack(file.getId(), 0);

        var updated = fileRepo.findById(file.getId()).orElseThrow();
        assertThat(updated.getVocalTrackIndex()).isEqualTo(0);
        assertThat(updated.getVocalConfidence()).isEqualTo("HIGH");
        // 已确认 → 脱离复核列表
        assertThat(adminService.listVocalReview(0, 20).getTotalElements()).isZero();
    }

    @Test
    void confirmVocalTrackRejectsOutOfRangeIndex() {
        Song ktv = songRepo.findByFingerprint("fp-晴天").orElseThrow();
        var file = fileRepo.save(vocalFile(ktv.getId(), "/music/晴天.mkv", 2, 1, "LOW"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adminService.confirmVocalTrack(file.getId(), 5))
                .hasMessageContaining("越界");
    }

    private com.homektv.domain.SongFile vocalFile(Long songId, String path, int tracks,
                                                  Integer vocalIdx, String confidence) {
        var f = new com.homektv.domain.SongFile();
        f.setSongId(songId); f.setFilePath(path); f.setFormat("matroska");
        f.setAudioTracks(tracks); f.setVocalTrackIndex(vocalIdx); f.setVocalConfidence(confidence);
        f.setFileMtime(java.time.OffsetDateTime.now());
        return f;
    }

    private com.homektv.domain.SongFile adminFile(Long songId, String path, boolean transcode, String sourcePath) {
        var f = new com.homektv.domain.SongFile();
        f.setSongId(songId); f.setFilePath(path); f.setFormat("mkv");
        f.setFileMtime(java.time.OffsetDateTime.now()); f.setTranscodeRequired(transcode);
        f.setSourcePath(sourcePath);
        return f;
    }

    @Test
    void nicknameConflictGetsSuffix() {
        userService.upsert("tok-a", "小明");
        var second = userService.upsert("tok-b", "小明");
        assertThat(second.getNickname()).isEqualTo("小明#2");
    }
}
