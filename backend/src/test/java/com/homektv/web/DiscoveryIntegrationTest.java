package com.homektv.web;

import com.homektv.domain.PlayHistory;
import com.homektv.domain.Song;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.WishRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 发现类 API 集成测试：点唱排行、最新入库、心愿单、历史（P3.2-P3.4）。
 */
@SpringBootTest
@Testcontainers
@Isolated("Testcontainers JUnit extension does not support parallel test execution")
class DiscoveryIntegrationTest {

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

    @Autowired DiscoveryController discoveryController;
    @Autowired ApplicationContext applicationContext;
    @Autowired Environment environment;
    @Autowired HistoryController historyController;
    @Autowired WishController wishController;
    @Autowired SongRepository songRepo;
    @Autowired PlayHistoryRepository historyRepo;
    @Autowired WishRepository wishRepo;

    private Long songA, songB;

    @BeforeEach
    void seed() {
        historyRepo.deleteAll();
        wishRepo.deleteAll();
        songRepo.deleteAll();
        songA = save("晴天", "fpA");
        songB = save("后来", "fpB");
        // songA 播 3 次，songB 播 1 次
        for (int i = 0; i < 3; i++) history(songA);
        history(songB);
    }

    private Long save(String title, String fp) {
        Song s = new Song();
        s.setTitle(title); s.setArtist("测试"); s.setMediaType("KTV_VIDEO");
        s.setStatus("ok"); s.setFingerprint(fp);
        return songRepo.save(s).getId();
    }

    private void history(Long songId) {
        PlayHistory h = new PlayHistory();
        h.setSongId(songId);
        historyRepo.save(h);
    }

    @Test
    void rankingOrdersByPlayCount() {
        List<?> r = discoveryController.ranking(30);
        assertThat(r).isNotEmpty();
        // songA 播放最多，应排第一
        var first = (com.homektv.web.dto.SongDto) r.get(0);
        assertThat(first.title()).isEqualTo("晴天");
    }

    @Test
    void testContextDisablesBackgroundSchedulingAndLanDiscovery() {
        assertThat(environment.getProperty("app.scheduling.enabled", Boolean.class, true)).isFalse();
        assertThat(environment.getProperty("app.discovery.enabled", Boolean.class, true)).isFalse();
        assertThat(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                .isEmpty();
    }

    @Test
    void newSongsReturnsAll() {
        assertThat(discoveryController.newSongs()).hasSize(2);
    }

    @Test
    void historyReturnsRecent() {
        assertThat(historyController.history()).isNotEmpty();
    }

    @Test
    void wishAddAndList() {
        wishController.add(java.util.Map.of("keyword", "想唱的歌", "client_token", "tok-w"));
        assertThat(wishController.list()).extracting("keyword").contains("想唱的歌");
    }
}
