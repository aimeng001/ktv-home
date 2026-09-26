package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.domain.SongArtist;
import com.homektv.repo.SongArtistRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Isolated("Testcontainers JUnit extension does not support parallel test execution")
class CollaborativeArtistIntegrationTest {

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

    @Autowired SongRepository songRepository;
    @Autowired SongArtistRepository artistRepository;
    @Autowired ArtistCreditService artistCredits;
    @Autowired ArtistCreditReconciliationService creditReconciliation;
    @Autowired CategoryBrowseService browseService;
    @Autowired SongSearchService searchService;
    @Autowired SongMergeService mergeService;

    @BeforeEach
    void clean() {
        artistRepository.deleteAll();
        songRepository.deleteAll();
    }

    @Test
    void oneCollaborativeSongIsVisibleUnderEachArtistAndSearchesOnlyOnce() {
        Song song = save("合唱歌曲", "单依纯_王子异", "collaborative-1");
        artistCredits.replace(song.getId(), List.of("单依纯", "王子异"));

        assertThat(artistRepository.findBySongIdOrderByArtistOrder(song.getId()))
                .extracting(SongArtist::getArtistName)
                .containsExactly("单依纯", "王子异");
        assertThat(browseService.artists()).extracting(row -> row.get("name"))
                .contains("单依纯", "王子异")
                .doesNotContain("单依纯_王子异");
        assertThat(songRepository.findDistinctArtistByStatus("ok"))
                .contains("单依纯", "王子异")
                .doesNotContain("单依纯_王子异");

        List<SongDto> bySecondArtist = browseService.songs(
                "王子异", "", "", "", "", "title", 100);
        assertThat(bySecondArtist).extracting(SongDto::title)
                .containsExactly("合唱歌曲");

        assertThat(searchService.search("wzy", 0)).extracting(Song::getTitle)
                .containsExactly("合唱歌曲");
    }

    @Test
    void startupReconciliationRepairsMigrationRowsBeforePinyinSearch() {
        Song song = save("合唱歌曲", "单依纯_王子异", "collaborative-legacy-search");
        SongArtist legacy = new SongArtist();
        legacy.setSongId(song.getId());
        legacy.setArtistName("王子异");
        legacy.setArtistKey(ArtistCreditParser.key("王子异"));
        legacy.setArtistPy("");
        legacy.setArtistInit("");
        legacy.setArtistOrder(1);
        artistRepository.saveAndFlush(legacy);

        creditReconciliation.reconcileValidSongs();

        assertThat(artistRepository.findBySongIdOrderByArtistOrder(song.getId()))
                .extracting(SongArtist::getArtistInit)
                .contains("wzy");
        assertThat(searchService.search("wzy", 0)).extracting(Song::getTitle)
                .containsExactly("合唱歌曲");
    }

    @Test
    void mergingSongsTransfersAllArtistCreditsBeforeDeletingTheSource() {
        Song keep = save("合唱歌曲", "单依纯", "collaborative-keep");
        Song source = save("合唱歌曲", "王子异", "collaborative-source");
        artistCredits.replace(keep.getId(), "单依纯");
        artistCredits.replace(source.getId(), "王子异");

        mergeService.merge(keep.getId(), source.getId());

        assertThat(songRepository.findById(source.getId())).isEmpty();
        assertThat(artistRepository.findBySongIdOrderByArtistOrder(keep.getId()))
                .extracting(SongArtist::getArtistName)
                .containsExactly("单依纯", "王子异");
    }

    @Test
    void mergingCollaborativeKeepDoesNotCreateCombinedArtistCredit() {
        Song keep = save("合唱歌曲", "单依纯_王子异", "collaborative-keep-combined");
        Song source = save("合唱歌曲", "王子异", "collaborative-source-duplicate");
        artistCredits.replace(keep.getId(), "单依纯_王子异");
        artistCredits.replace(source.getId(), "王子异");

        mergeService.merge(keep.getId(), source.getId());

        assertThat(artistRepository.findBySongIdOrderByArtistOrder(keep.getId()))
                .extracting(SongArtist::getArtistName)
                .containsExactly("单依纯", "王子异");
    }

    @Test
    void mergingLegacySourceAlsoBuildsMissingIndependentCredits() {
        Song keep = save("合唱歌曲", "单依纯", "collaborative-keep-legacy");
        Song source = save("合唱歌曲", "单依纯_王子异", "collaborative-source-legacy");
        artistCredits.replace(keep.getId(), "单依纯");

        mergeService.merge(keep.getId(), source.getId());

        assertThat(artistRepository.findBySongIdOrderByArtistOrder(keep.getId()))
                .extracting(SongArtist::getArtistName)
                .containsExactly("单依纯", "王子异");
    }

    private Song save(String title, String artist, String fingerprint) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setMediaType("KTV_VIDEO");
        song.setStatus("ok");
        song.setFingerprint(fingerprint);
        return songRepository.saveAndFlush(song);
    }
}
