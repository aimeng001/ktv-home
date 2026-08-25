package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 曲库扫描入库端到端集成测试（P1.1-P1.5 验收）。
 * 用 ffmpeg 生成「双音轨视频(KTV)」「单音轨视频(MV)」「纯音频(AUDIO)」三类文件，
 * 扫描后验证类型判定、拼音字段、去重、多文件源优先级。
 */
@SpringBootTest
@Testcontainers
class LibraryScanIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ktv").withUsername("ktv").withPassword("ktv");

    static final Path libraryDir;
    static boolean ffmpegAvailable;

    static {
        try {
            libraryDir = Files.createTempDirectory("ktv-lib");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("app.source-library-path", () -> libraryDir.toString());
        registry.add("app.ktv-library-path", () -> libraryDir.toString());
        registry.add("app.data-path", () -> libraryDir.resolve("_data").toString());
    }

    @BeforeAll
    static void genMedia() throws Exception {
        ffmpegAvailable = commandExists("ffmpeg");
        if (!ffmpegAvailable) return;

        // 双音轨视频（KTV_VIDEO），文件名带歌手-歌名
        run("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=640x360:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=880:duration=2",
                "-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "libx264", "-preset", "ultrafast",
                libraryDir.resolve("周杰伦 - 晴天.mkv").toString());
        // 单音轨视频（MV）
        run("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=640x360:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "ultrafast",
                libraryDir.resolve("Beyond - 海阔天空.mp4").toString());
        // 纯音频（AUDIO）
        run("ffmpeg", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                libraryDir.resolve("刘若英 - 后来.mp3").toString());
    }

    @Autowired LibraryScanService scanService;
    @Autowired SongRepository songRepo;
    @Autowired SongFileRepository fileRepo;
    @Autowired JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void clean() {
        // 每个测试从空库开始，避免测试间 song_files/mtime 状态交叉导致 skip 计数不稳定
        fileRepo.deleteAll();
        songRepo.deleteAll();
        try {
            Files.deleteIfExists(libraryDir.resolve("周杰伦 - 晴天.lrc"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void scanClassifiesAndIngests() {
        assumeTrue(ffmpegAvailable, "ffmpeg 不可用，跳过扫描集成测试");

        LibraryScanService.ScanResult result = scanService.scanAll();
        assertThat(result.scanned()).isGreaterThanOrEqualTo(3);

        List<Song> songs = songRepo.findAll();
        assertThat(songs).hasSizeGreaterThanOrEqualTo(3);

        Song qingtian = songs.stream().filter(s -> s.getTitle().equals("晴天")).findFirst().orElseThrow();
        assertThat(qingtian.getMediaType()).isEqualTo(MediaClassifier.KTV_VIDEO);
        assertThat(qingtian.isHasVocalTrack()).isTrue();
        // 拼音字段（P1.5）
        assertThat(qingtian.getArtistInit()).isEqualTo("zjl");
        assertThat(qingtian.getTitlePy()).isEqualTo("qingtian");

        Song hokt = songs.stream().filter(s -> s.getTitle().equals("海阔天空")).findFirst().orElseThrow();
        assertThat(hokt.getMediaType()).isEqualTo(MediaClassifier.MV);
        assertThat(hokt.isHasVocalTrack()).isFalse();

        Song houlai = songs.stream().filter(s -> s.getTitle().equals("后来")).findFirst().orElseThrow();
        assertThat(houlai.getMediaType()).isEqualTo(MediaClassifier.AUDIO);

        // 每首都有对应文件源
        List<SongFile> ktvFiles = fileRepo.findBySongIdOrderByPriorityDesc(qingtian.getId());
        assertThat(ktvFiles).isNotEmpty();
        assertThat(ktvFiles.get(0).getPriority()).isEqualTo(100); // KTV 优先级最高
    }

    @Test
    void persistsRelativePathForScannedFile() {
        assumeTrue(ffmpegAvailable, "ffmpeg 不可用，跳过扫描集成测试");

        scanService.scanAll();

        String relativePath = jdbcTemplate.queryForObject(
                "SELECT relative_path FROM song_files WHERE file_path = ?",
                String.class,
                libraryDir.resolve("周杰伦 - 晴天.mkv").toString());
        assertThat(relativePath).isEqualTo("周杰伦 - 晴天.mkv");
    }

    @Test
    void rescanIsIdempotent() {
        assumeTrue(ffmpegAvailable, "ffmpeg 不可用，跳过");
        scanService.scanAll();
        long countAfterFirst = songRepo.count();
        // 再次扫描：文件未变，应全部跳过，歌曲数不增
        LibraryScanService.ScanResult second = scanService.scanAll();
        assertThat(songRepo.count()).isEqualTo(countAfterFirst);
        assertThat(second.skipped()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void importsAndUpdatesEnhancedLrcSidecar() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg 不可用，跳过");
        Path lyric = libraryDir.resolve("周杰伦 - 晴天.lrc");
        Files.writeString(lyric, "[00:01.00]<00:01.00>晴<00:01.50>天\n");

        scanService.scanAll();
        Song song = songRepo.findAll().stream()
                .filter(s -> s.getTitle().equals("晴天"))
                .findFirst().orElseThrow();
        assertThat(song.getLyricType()).isEqualTo(LyricType.WORD);
        Path cached = libraryDir.resolve("_data").resolve(song.getLyricPath());
        assertThat(Files.readString(cached)).contains("<00:01.50>天");

        Thread.sleep(1100);
        Files.writeString(lyric, "[00:02.00]<00:02.00>新<00:02.50>词\n");
        LibraryScanService.ScanResult updated = scanService.scanAll();

        assertThat(updated.updated()).isGreaterThanOrEqualTo(1);
        assertThat(Files.readString(cached)).contains("<00:02.50>词");
    }

    @Test
    void ignoresInvalidLrcSidecar() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg 不可用，跳过");
        Files.writeString(libraryDir.resolve("周杰伦 - 晴天.lrc"), "没有时间标签\n");

        scanService.scanAll();
        Song song = songRepo.findAll().stream()
                .filter(s -> s.getTitle().equals("晴天"))
                .findFirst().orElseThrow();

        assertThat(song.getLyricType()).isEqualTo(LyricType.NONE);
        assertThat(song.getLyricPath()).isNull();
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(60, TimeUnit.SECONDS);
    }
}
