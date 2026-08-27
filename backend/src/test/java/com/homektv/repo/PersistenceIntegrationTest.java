package com.homektv.repo;

import com.homektv.domain.PlayerState;
import com.homektv.domain.Song;
import com.homektv.library.SongMergeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 PostgreSQL（Testcontainers）验证：
 * 1) Flyway 迁移 + JPA ddl-auto=validate 通过（实体映射与表结构一致）；
 * 2) player_state 单行已初始化；
 * 3) Song 实体（含 text[] tags）可正常读写。
 */
@SpringBootTest
@Testcontainers
class PersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ktv")
                    .withUsername("ktv")
                    .withPassword("ktv");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private PlayerStateRepository playerStateRepository;

    @Autowired
    private SongMergeService songMergeService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void playerStateSingletonInitialized() {
        PlayerState ps = playerStateRepository.getSingleton();
        assertThat(ps.getId()).isEqualTo(PlayerState.SINGLETON_ID);
        assertThat(ps.getState()).isEqualTo("idle");
        assertThat(ps.getVolume()).isEqualTo(60);
        assertThat(ps.getVocalMode()).isEqualTo("accompaniment");
    }

    @Test
    void songRoundTripWithTagsArray() {
        Song s = new Song();
        s.setTitle("晴天");
        s.setArtist("周杰伦");
        s.setTitlePy("qingtian");
        s.setTitleInit("qt");
        s.setArtistPy("zhoujielun");
        s.setArtistInit("zjl");
        s.setMediaType("KTV_VIDEO");
        s.setHasVocalTrack(true);
        s.setLyricType("word");
        s.setTags(new String[]{"华语", "经典"});
        s.setFingerprint("test-fp-001");

        Song saved = songRepository.save(s);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        Song found = songRepository.findByFingerprint("test-fp-001").orElseThrow();
        assertThat(found.getTitle()).isEqualTo("晴天");
        assertThat(found.getTags()).containsExactly("华语", "经典");
        assertThat(found.getLyricType()).isEqualTo("word");
        assertThat(found.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_UNKNOWN);
        assertThat(found.isHasVocalTrack()).isTrue();
    }

    @Test
    void mergeSongMigratesReferencesAndDeduplicatesUserLists() {
        String suffix = String.valueOf(System.nanoTime());
        Song keep = songRepository.save(song("保留歌曲", "歌手甲", "merge-keep-" + suffix));
        Song source = songRepository.save(song("重复歌曲", "歌手乙", "merge-source-" + suffix));
        source.setLyricPath("lyrics/source.lrc");
        source.setLyricType("line");
        source.setLyricSource(Song.LYRIC_SOURCE_SIDECAR);
        songRepository.save(source);
        Long userId = jdbc.queryForObject("INSERT INTO users (client_token, nickname) VALUES (?, ?) RETURNING id",
                Long.class, "merge-user-" + suffix, "测试用户");
        Long playlistId = jdbc.queryForObject("INSERT INTO playlists (name) VALUES (?) RETURNING id",
                Long.class, "合并测试-" + suffix);
        jdbc.update("INSERT INTO favorites (user_id, song_id) VALUES (?, ?), (?, ?)",
                userId, keep.getId(), userId, source.getId());
        jdbc.update("INSERT INTO playlist_songs (playlist_id, song_id, sort_order, manual) VALUES (?, ?, 8, false), (?, ?, 3, true)",
                playlistId, keep.getId(), playlistId, source.getId());
        jdbc.update("INSERT INTO queue (song_id, order_index) VALUES (?, 1)", source.getId());
        jdbc.update("INSERT INTO play_history (song_id) VALUES (?)", source.getId());

        songMergeService.merge(keep.getId(), source.getId());

        assertThat(songRepository.findById(source.getId())).isEmpty();
        Song merged = songRepository.findById(keep.getId()).orElseThrow();
        assertThat(merged.getLyricPath()).isEqualTo("lyrics/source.lrc");
        assertThat(merged.getLyricType()).isEqualTo("line");
        assertThat(merged.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites WHERE user_id = ? AND song_id = ?", Long.class, userId, keep.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playlist_songs WHERE playlist_id = ? AND song_id = ?", Long.class, playlistId, keep.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT manual FROM playlist_songs WHERE playlist_id = ? AND song_id = ?", Boolean.class, playlistId, keep.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT song_id FROM queue WHERE order_index = 1", Long.class)).isEqualTo(keep.getId());
        assertThat(jdbc.queryForObject("SELECT song_id FROM play_history ORDER BY id DESC LIMIT 1", Long.class)).isEqualTo(keep.getId());
    }

    private Song song(String title, String artist, String fingerprint) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setMediaType("AUDIO");
        song.setFingerprint(fingerprint);
        return song;
    }
}
