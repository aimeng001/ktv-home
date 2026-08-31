package com.homektv.web;

import com.homektv.domain.Song;
import com.homektv.library.PinyinUtil;
import com.homektv.library.SongSearchService;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 综合搜索集成测试（P1.6 验收）。重点验证拼音四条件，
 * 特别是「zjl→周杰伦」（歌手首字母）这一 PRD 承诺但曾在 SQL 中缺失的场景。
 */
@SpringBootTest
@Testcontainers
class SearchIntegrationTest {

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

    @Autowired SongSearchService searchService;
    @Autowired SongRepository songRepo;

    @BeforeEach
    void seed() {
        songRepo.deleteAll();
        save("晴天", "周杰伦", "KTV_VIDEO", 100, "国语", new String[]{"流行"});
        save("七里香", "周杰伦", "KTV_VIDEO", 80);
        save("后来", "刘若英", "AUDIO", 60);
        save("海阔天空", "Beyond", "MV", 50, "粤语", new String[]{"摇滚"});
    }

    private void save(String title, String artist, String mediaType, int playCount) {
        save(title, artist, mediaType, playCount, "未知", new String[0]);
    }

    private void save(String title, String artist, String mediaType, int playCount,
                      String language, String[] tags) {
        Song s = new Song();
        s.setTitle(title);
        s.setArtist(artist);
        s.setTitlePy(PinyinUtil.fullPinyin(title));
        s.setTitleInit(PinyinUtil.initials(title));
        s.setArtistPy(PinyinUtil.fullPinyin(artist));
        s.setArtistInit(PinyinUtil.initials(artist));
        s.setLanguage(language);
        s.setTags(tags);
        s.setMediaType(mediaType);
        s.setPlayCount(playCount);
        s.setFingerprint("fp-" + title);
        songRepo.save(s);
    }

    @Test
    void searchByChinese() {
        List<Song> r = searchService.search("晴天", 0);
        assertThat(r).extracting(Song::getTitle).contains("晴天");
    }

    @Test
    void searchByFullPinyin() {
        List<Song> r = searchService.search("qingtian", 0);
        assertThat(r).extracting(Song::getTitle).contains("晴天");
    }

    @Test
    void searchByTitleInitials() {
        List<Song> r = searchService.search("qt", 0);
        assertThat(r).extracting(Song::getTitle).contains("晴天");
    }

    @Test
    void searchByArtistInitials_zjl() {
        // 关键场景：歌手首字母 → 应搜到该歌手全部歌曲
        List<Song> r = searchService.search("zjl", 0);
        assertThat(r).extracting(Song::getTitle).contains("晴天", "七里香");
    }

    @Test
    void searchByArtistFullPinyin() {
        List<Song> r = searchService.search("zhoujielun", 0);
        assertThat(r).extracting(Song::getTitle).contains("晴天", "七里香");
    }

    @Test
    void searchByLanguage() {
        List<Song> r = searchService.search("国语", 0);
        assertThat(r).extracting(Song::getTitle).contains("晴天");
    }

    @Test
    void searchByCategoryTag() {
        List<Song> r = searchService.search("摇滚", 0);
        assertThat(r).extracting(Song::getTitle).contains("海阔天空");
    }

    @Test
    void ktvRanksBeforeOthersAndExactFirst() {
        // 完全匹配「后来」应在前；KTV 版整体优先
        List<Song> r = searchService.search("后来", 0);
        assertThat(r).isNotEmpty();
        assertThat(r.get(0).getTitle()).isEqualTo("后来");
    }

    @Test
    void mediaTypeFilterIsAppliedBeforeThePageLimit() {
        for (int i = 0; i < 50; i++) {
            save("分页歌曲" + i, "测试歌手" + i, "KTV_VIDEO", 100 - i);
        }
        save("分页歌曲目标", "测试歌手", "MV", 1);

        List<Song> result = searchService.search("分页歌曲", "MV", 0);

        assertThat(result).extracting(Song::getTitle).containsExactly("分页歌曲目标");
    }

    @Test
    void emptyKeywordReturnsEmpty() {
        assertThat(searchService.search("", 0)).isEmpty();
        assertThat(searchService.search("   ", 0)).isEmpty();
    }
}
