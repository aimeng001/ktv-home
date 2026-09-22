package com.homektv.repo;

import com.homektv.domain.PlayerState;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.library.JdbcLibraryScanSeenPathStore;
import com.homektv.library.CategoryBrowseService;
import com.homektv.library.ArtistDirectoryProjectionService;
import com.homektv.library.ArtistGenderDictionaryService;
import com.homektv.library.ArtistGenderMatcher;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
    private SongFileRepository songFileRepository;

    @Autowired
    private PlaybackVariantRepository playbackVariantRepository;

    @Autowired
    private PlayerStateRepository playerStateRepository;

    @Autowired
    private SongMergeService songMergeService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcLibraryScanSeenPathStore scanSeenPathStore;

    @Autowired
    private CategoryBrowseService categoryBrowseService;

    @Autowired
    private ArtistDirectoryProjectionService artistDirectoryProjection;

    @Autowired
    private ArtistGenderDictionaryService artistGenderDictionary;

    @Autowired
    private ArtistGenderMatcher artistGenderMatcher;

    @Autowired
    private com.homektv.library.LibraryCatalogStatsService catalogStats;

    @Test
    void playerStateSingletonInitialized() {
        PlayerState ps = playerStateRepository.getSingleton();
        assertThat(ps.getId()).isEqualTo(PlayerState.SINGLETON_ID);
        assertThat(ps.getState()).isEqualTo("idle");
        assertThat(ps.getVolume()).isEqualTo(60);
        assertThat(ps.getVocalMode()).isEqualTo("accompaniment");
    }

    @Test
    void playbackVariantCreationLockExecutesAgainstPostgres() {
        org.assertj.core.api.Assertions.assertThatCode(() ->
                playbackVariantRepository.lockForCreation("persistence-integration-lock"))
                .doesNotThrowAnyException();
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


    @Test
    void testAggregationsAndBrowseQueriesAgainstRealPostgres() {
        String suffix = String.valueOf(System.nanoTime());
        Song s1 = new Song();
        s1.setTitle("测试歌曲一");
        s1.setArtist("张学友");
        s1.setArtistGender("男歌手");
        s1.setArtistInit("Z");
        s1.setLanguage("国语");
        s1.setAiVocalForm("独唱");
        s1.setTags(new String[]{"流行", "经典"});
        s1.setAiGenres(new String[]{"流行"});
        s1.setAiThemes(new String[]{"伤感"});
        s1.setMediaType("KTV_VIDEO");
        s1.setStatus("ok");
        s1.setFingerprint("test-agg-1-" + suffix);
        songRepository.save(s1);

        Song s2 = new Song();
        s2.setTitle("测试歌曲二");
        s2.setArtist("张学友");
        s2.setArtistGender("男歌手");
        s2.setArtistInit("Z");
        s2.setLanguage("粤语");
        s2.setAiVocalForm("独唱");
        s2.setTags(new String[]{"经典", "摇滚"});
        s2.setMediaType("KTV_VIDEO");
        s2.setStatus("ok");
        s2.setFingerprint("test-agg-2-" + suffix);
        songRepository.save(s2);

        // 1. Language aggregation
        var languages = songRepository.aggregateLanguagesByStatus("ok");
        assertThat(languages).isNotEmpty();

        // 2. Artist aggregation
        var artists = songRepository.aggregateArtistsByStatus("ok");
        assertThat(artists).isNotEmpty();
        assertThat(artists.stream().anyMatch(a -> "张学友".equals(a.getArtist()))).isTrue();

        // 3. Tags aggregation
        var tags = songRepository.aggregateTagsByStatusOk();
        assertThat(tags).isNotEmpty();
        assertThat(tags.stream().anyMatch(t -> "经典".equals(t.getName()))).isTrue();
        assertThat(tags.stream()
                .filter(t -> "流行".equals(t.getName()))
                .findFirst()
                .orElseThrow()
                .getSongCount()).isEqualTo(1L);

        // 4. Browse category songs
        var page = songRepository.browseCategorySongs("张学友", "男歌手", "国语", "流行", "独唱",
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("测试歌曲一");

        Song caseVariant = new Song();
        caseVariant.setTitle("大小写测试");
        caseVariant.setArtist("Case Artist");
        caseVariant.setArtistGender("Male");
        caseVariant.setLanguage("English");
        caseVariant.setAiVocalForm("Solo");
        caseVariant.setTags(new String[]{"Rock"});
        caseVariant.setMediaType("KTV_VIDEO");
        caseVariant.setStatus("ok");
        caseVariant.setFingerprint("test-agg-case-" + suffix);
        songRepository.save(caseVariant);

        var casePage = songRepository.browseCategorySongs("case artist", "male", "english", "rock", "solo",
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(casePage.getContent()).extracting(Song::getTitle).contains("大小写测试");

        // Native browse queries must map the public "new" sort to the physical
        // PostgreSQL column name; passing the Java property name would produce
        // s.createdAt and fail with SQLState 42703.
        assertThat(categoryBrowseService.songs("", "", "", "", "", "new", 10))
                .isNotEmpty();
        assertThat(categoryBrowseService.songs("", "", "", "", "", "", 10))
                .isNotEmpty();

        // 5. SongFile maxId query
        Long maxId = songFileRepository.findMaxIdByFileRole("LIBRARY");
        assertThat(maxId).isNotNull();
    }

    @Test
    void publicArtistDirectoryPaginatesAndFiltersByInitial() {
        String suffix = String.valueOf(System.nanoTime());
        String artist = "公共分页歌手-" + suffix;
        Song song = song(artist + "歌曲", artist, "public-artist-page-" + suffix);
        song.setArtistInit("zjl");
        song.setArtistGender("男歌手");
        song.setStatus("ok");
        songRepository.save(song);

        String key = artist.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        jdbc.update("""
                INSERT INTO artist_profiles(artist_key, display_name, gender, gender_status, artist_kind)
                VALUES (?, ?, '男歌手', 'MANUAL', 'PERSON')
                """, key, artist);

        var page = songRepository.pagePublicArtistDirectory(
                "ok", "男歌手", "Z", org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getName()).isEqualTo(artist);
        assertThat(page.getContent().getFirst().getInitial()).isEqualTo("Z");
        assertThat(page.getContent().getFirst().getAvatarPath()).isNull();

        assertThat(songRepository.findPublicArtistInitials("ok", "男歌手").stream()
                .map(SongRepository.ArtistInitialProjection::getInitial))
                .contains("Z");
    }

    @Test
    void artistDirectoryUsesProfileGenderInsteadOfContaminatedSongGender() {
        String suffix = String.valueOf(System.nanoTime());
        String maleKey = "gender-male-" + suffix;
        String femaleKey = "gender-female-" + suffix;
        String maleName = "性别甲-" + suffix;
        String femaleName = "性别乙-" + suffix;
        Song collaborative = songRepository.save(song(
                "性别污染合唱-" + suffix,
                maleName + "_" + femaleName,
                "gender-collaboration-" + suffix));
        collaborative.setArtistGender("女歌手");
        songRepository.save(collaborative);

        jdbc.update("""
                INSERT INTO artist_profiles(artist_key, display_name, gender, gender_status)
                VALUES (?, ?, '男歌手', 'MANUAL'), (?, ?, '未知', 'UNREVIEWED')
                """, maleKey, maleName, femaleKey, femaleName);
        jdbc.update("""
                INSERT INTO song_artists(song_id, artist_name, artist_key, artist_order)
                VALUES (?, ?, ?, 0), (?, ?, ?, 1)
                """, collaborative.getId(), maleName, maleKey,
                collaborative.getId(), femaleName, femaleKey);

        var malePage = songRepository.pageArtistDirectory(
                "ok", maleName, "男歌手", null,
                org.springframework.data.domain.PageRequest.of(0, 10));
        var femalePage = songRepository.pageArtistDirectory(
                "ok", femaleName, "男歌手", null,
                org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(malePage.getContent()).extracting(SongRepository.ArtistDirectoryProjection::getArtistKey)
                .containsExactly(maleKey);
        assertThat(femalePage.getContent()).isEmpty();

        var maleSongs = songRepository.browseCategorySongs(
                maleName, "男歌手", "", "", "",
                org.springframework.data.domain.PageRequest.of(0, 10));
        var femaleSongs = songRepository.browseCategorySongs(
                femaleName, "男歌手", "", "", "",
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(maleSongs.getContent()).extracting(Song::getId).containsExactly(collaborative.getId());
        assertThat(femaleSongs.getContent()).isEmpty();

        var canonicalMaleSongs = songRepository.browseCategorySongsByArtistKey(
                maleKey, "男歌手", "", "", "",
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(canonicalMaleSongs.getContent()).extracting(Song::getId)
                .containsExactly(collaborative.getId());
    }

    @Test
    void artistDirectoryProjectionInfersSingleCreditGenderAndServesIndexedPage() {
        String suffix = String.valueOf(System.nanoTime());
        String artist = "投影歌手-" + suffix;
        Song song = song("投影歌曲-" + suffix, artist, "projection-" + suffix);
        song.setArtistInit("zz");
        song.setArtistGender("男歌手");
        song.setStatus("ok");
        songRepository.save(song);

        artistDirectoryProjection.refresh();

        var page = artistDirectoryProjection.page("男歌手", "Z", 0, 100).orElseThrow();
        assertThat(page.rows()).anySatisfy(row -> {
            assertThat(row.name()).isEqualTo(artist);
            assertThat(row.gender()).isEqualTo("男歌手");
            assertThat(row.songCount()).isGreaterThanOrEqualTo(1L);
        });
    }

    @Test
    void databaseDictionaryMatchesAliasesKeepsUnknownAndNeverOverwritesManualProfiles() {
        String suffix = String.valueOf(System.nanoTime());
        String maleName = "数据库男歌手-" + suffix;
        String aliasName = "数据库男别名-" + suffix;
        String manualName = "数据库人工歌手-" + suffix;
        String unknownName = "数据库未知歌手-" + suffix;
        jdbc.update("""
                INSERT INTO artist_profiles(artist_key, display_name, gender, gender_status, artist_kind)
                VALUES (?, ?, '未知', 'UNREVIEWED', 'PERSON'),
                       (?, ?, '未知', 'UNREVIEWED', 'PERSON'),
                       (?, ?, '男歌手', 'MANUAL', 'PERSON'),
                       (?, ?, '未知', 'UNREVIEWED', 'PERSON')
                """,
                com.homektv.library.ArtistCreditParser.key(maleName), maleName,
                com.homektv.library.ArtistCreditParser.key(aliasName), aliasName,
                com.homektv.library.ArtistCreditParser.key(manualName), manualName,
                com.homektv.library.ArtistCreditParser.key(unknownName), unknownName);
        artistGenderDictionary.importEntries(List.of(
                new ArtistGenderDictionaryService.DictionaryEntry(maleName, "男歌手", List.of(aliasName)),
                new ArtistGenderDictionaryService.DictionaryEntry(manualName, "女歌手", List.of())
        ));
        int changed = artistGenderMatcher.applyDictionary();

        assertThat(changed).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT gender FROM artist_profiles WHERE artist_key = ?",
                String.class, com.homektv.library.ArtistCreditParser.key(maleName))).isEqualTo("男歌手");
        assertThat(jdbc.queryForObject("SELECT gender FROM artist_profiles WHERE artist_key = ?",
                String.class, com.homektv.library.ArtistCreditParser.key(aliasName))).isEqualTo("男歌手");
        assertThat(jdbc.queryForObject("SELECT gender_status FROM artist_profiles WHERE artist_key = ?",
                String.class, com.homektv.library.ArtistCreditParser.key(manualName))).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT gender FROM artist_profiles WHERE artist_key = ?",
                String.class, com.homektv.library.ArtistCreditParser.key(unknownName))).isEqualTo("未知");
    }
@Test
    void consistentSongGenderClassifiesProfilesAndUpdatesPublicProjection() {
        String suffix = String.valueOf(System.nanoTime());
        String consistentArtist = "一致女歌手-" + suffix;
        String mixedArtist = "冲突分类歌手-" + suffix;
        Song consistent = song("一致歌曲-" + suffix, consistentArtist, "gender-fallback-consistent-" + suffix);
        consistent.setArtistGender("女歌手");
        consistent.setArtistInit("C");
        consistent.setStatus("ok");
        songRepository.save(consistent);

        Song mixedMale = song("冲突男歌曲-" + suffix, mixedArtist, "gender-fallback-male-" + suffix);
        mixedMale.setArtistGender("男歌手");
        mixedMale.setStatus("ok");
        songRepository.save(mixedMale);
        Song mixedFemale = song("冲突女歌曲-" + suffix, mixedArtist, "gender-fallback-female-" + suffix);
        mixedFemale.setArtistGender("女歌手");
        mixedFemale.setStatus("ok");
        songRepository.save(mixedFemale);

        jdbc.update("""
                INSERT INTO artist_profiles(artist_key, display_name, gender, gender_status, artist_kind)
                VALUES (?, ?, '未知', 'UNREVIEWED', 'PERSON'),
                       (?, ?, '未知', 'UNREVIEWED', 'PERSON')
                """,
                com.homektv.library.ArtistCreditParser.key(consistentArtist), consistentArtist,
                com.homektv.library.ArtistCreditParser.key(mixedArtist), mixedArtist);

        int changed = artistGenderMatcher.applyDictionary();

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM artist_profiles
                WHERE artist_key IN (?, ?)
                  AND gender = '女歌手'
                  AND gender_status = 'AUTO_DB'
                """, Long.class,
                com.homektv.library.ArtistCreditParser.key(consistentArtist),
                com.homektv.library.ArtistCreditParser.key(mixedArtist))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT gender FROM artist_profiles WHERE artist_key = ?",
                String.class, com.homektv.library.ArtistCreditParser.key(consistentArtist))).isEqualTo("女歌手");
        assertThat(jdbc.queryForObject("SELECT gender FROM artist_profiles WHERE artist_key = ?",
                String.class, com.homektv.library.ArtistCreditParser.key(mixedArtist))).isEqualTo("未知");

        artistDirectoryProjection.refresh();
        var page = artistDirectoryProjection.page("女歌手", "C", 0, 100).orElseThrow();
        assertThat(page.rows()).anySatisfy(row -> assertThat(row.name()).isEqualTo(consistentArtist));
    }
    @Test
    void collaborationCreditsDoNotInferGroupGender() {
        String suffix = String.valueOf(System.nanoTime());
        String linkedMale = "合作甲-" + suffix;
        String linkedFemale = "合作乙-" + suffix;
        String legacyCredit = linkedMale + "_" + linkedFemale;

        Song linkedSong = song("合作歌曲-" + suffix, legacyCredit, "gender-collaboration-linked-" + suffix);
        linkedSong.setArtistGender("组合");
        linkedSong.setStatus("ok");
        Song savedLinked = songRepository.save(linkedSong);
        jdbc.update("""
                INSERT INTO song_artists(song_id, artist_name, artist_key, artist_order)
                VALUES (?, ?, ?, 0), (?, ?, ?, 1)
                """,
                savedLinked.getId(), linkedMale, com.homektv.library.ArtistCreditParser.key(linkedMale),
                savedLinked.getId(), linkedFemale, com.homektv.library.ArtistCreditParser.key(linkedFemale));

        Song legacySong = song("遗留合作歌曲-" + suffix, legacyCredit, "gender-collaboration-legacy-" + suffix);
        legacySong.setArtistGender("组合");
        legacySong.setStatus("ok");
        songRepository.save(legacySong);

        jdbc.update("""
                INSERT INTO artist_profiles(artist_key, display_name, gender, gender_status, artist_kind)
                VALUES (?, ?, '未知', 'UNREVIEWED', 'PERSON'),
                       (?, ?, '未知', 'UNREVIEWED', 'PERSON'),
                       (?, ?, '未知', 'UNREVIEWED', 'PERSON')
                """,
                com.homektv.library.ArtistCreditParser.key(linkedMale), linkedMale,
                com.homektv.library.ArtistCreditParser.key(linkedFemale), linkedFemale,
                com.homektv.library.ArtistCreditParser.key(legacyCredit), legacyCredit);

        artistGenderMatcher.applyDictionary();

        assertThat(jdbc.queryForObject("SELECT gender FROM artist_profiles WHERE artist_key = ?",
                String.class, com.homektv.library.ArtistCreditParser.key(linkedMale))).isEqualTo("未知");
        assertThat(jdbc.queryForObject("SELECT gender FROM artist_profiles WHERE artist_key = ?",
                String.class, com.homektv.library.ArtistCreditParser.key(linkedFemale))).isEqualTo("未知");
        assertThat(jdbc.queryForObject("SELECT gender FROM artist_profiles WHERE artist_key = ?",
                String.class, com.homektv.library.ArtistCreditParser.key(legacyCredit))).isEqualTo("未知");
    }

    @Test
    void libraryCatalogStatsRefreshUsesRoleScopedReadyCounts() {
        String suffix = String.valueOf(System.nanoTime());
        Song song = song("状态统计歌曲-" + suffix, "状态统计歌手", "stats-" + suffix);
        song.setStatus("ok");
        Song saved = songRepository.save(song);
        SongFile file = songFile("/stats-" + suffix + ".mkv", saved.getId(), true);
        file.setMediaType("KTV_VIDEO");
        file.setProbePending(false);
        songFileRepository.save(file);

        catalogStats.refresh();

        var stats = catalogStats.find().orElseThrow();
        assertThat(stats.totalSongs()).isGreaterThanOrEqualTo(1L);
        assertThat(stats.indexedSongs()).isGreaterThanOrEqualTo(1L);
        assertThat(stats.readySongs()).isGreaterThanOrEqualTo(1L);
        assertThat(stats.probePendingFiles()).isGreaterThanOrEqualTo(0L);
    }
    @Test
    void scanSeenPathStoreKeepsReconciliationBoundedAndPendingQueueScanScoped() {
        String suffix = String.valueOf(System.nanoTime());
        Song seenSong = songRepository.save(song("扫描已见", "扫描歌手", "scan-seen-" + suffix));
        Song unseenSong = songRepository.save(song("扫描未见", "扫描歌手", "scan-unseen-" + suffix));
        Song outsideSong = songRepository.save(song("扫描外部", "扫描歌手", "scan-outside-" + suffix));

        SongFile seen = songFile("/music/seen-" + suffix + ".mkv", seenSong.getId(), true);
        seen.setProbePending(true);
        SongFile unseen = songFile("/music/unseen-" + suffix + ".mkv", unseenSong.getId(), true);
        unseen.setProbePending(true);
        SongFile outside = songFile("/other-library/outside-" + suffix + ".mkv", outsideSong.getId(), true);
        outside.setProbePending(true);
        songFileRepository.saveAll(List.of(seen, unseen, outside));
        songFileRepository.flush();

        UUID scanId = UUID.randomUUID();
        scanSeenPathStore.recordBatch(scanId, "LIBRARY", List.of(seen.getFilePath()));
        scanSeenPathStore.recordPendingBatch(scanId, "LIBRARY", List.of(seen.getFilePath()));

        var pending = songFileRepository.findPendingForScan("LIBRARY", 0L, scanId, "",
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(pending.getContent()).extracting(SongFile::getFilePath)
                .containsExactly(seen.getFilePath());
        assertThat(scanSeenPathStore.findPendingAtStart(scanId, "LIBRARY",
                List.of(seen.getFilePath(), unseen.getFilePath())))
                .containsExactly(seen.getFilePath());

        var missing = scanSeenPathStore.markMissing(scanId, "LIBRARY", "/music");
        assertThat(missing.filesMarked()).isEqualTo(1);
        assertThat(missing.songsMarked()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT valid FROM song_files WHERE id = ?", Boolean.class, unseen.getId()))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT valid FROM song_files WHERE id = ?", Boolean.class, outside.getId()))
                .isTrue();

        scanSeenPathStore.delete(scanId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM library_scan_seen_paths WHERE scan_id = ?",
                Long.class, scanId)).isZero();
    }

    private SongFile songFile(String path, Long songId, boolean valid) {
        SongFile file = new SongFile();
        file.setSongId(songId);
        file.setFilePath(path);
        file.setFileRole("LIBRARY");
        file.setFormat("mkv");
        file.setFileMtime(OffsetDateTime.now());
        file.setValid(valid);
        return file;
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
