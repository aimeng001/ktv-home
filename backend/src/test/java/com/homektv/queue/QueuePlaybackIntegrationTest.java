package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.PlayerState;
import com.homektv.domain.PlayHistory;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
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
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * 队列状态机与播放控制集成测试（P1.9-P1.12 验收，详设§4.4/§9.2）。
 */
@SpringBootTest
@Testcontainers
@Isolated("Testcontainers JUnit extension does not support parallel test execution")
class QueuePlaybackIntegrationTest {

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

    @Autowired QueueService queueService;
    @Autowired PlaybackService playbackService;
    @Autowired SongRepository songRepo;
    @Autowired QueueItemRepository queueRepo;
    @Autowired PlayerStateRepository playerRepo;
    @Autowired AppUserRepository userRepo;
    @Autowired SongFileRepository fileRepo;
    @Autowired com.homektv.repo.PlayHistoryRepository historyRepo;

    private Long song1, song2, song3;
    private Long user1;

    @BeforeEach
    void seed() {
        // 先重置播放状态解除对 queue 的外键引用，再按依赖顺序清理
        PlayerState ps = playerRepo.getSingleton();
        ps.setCurrentQueueId(null);
        ps.setState("idle");
        playerRepo.save(ps);

        historyRepo.deleteAll();
        queueRepo.deleteAll();
        fileRepo.deleteAll();
        songRepo.deleteAll();
        userRepo.deleteAll();

        AppUser u = new AppUser();
        u.setClientToken("tok-test-1");
        u.setNickname("小明");
        user1 = userRepo.save(u).getId();

        song1 = save("晴天", "fp1");
        song2 = save("七里香", "fp2");
        song3 = save("后来", "fp3");
    }

    @org.junit.jupiter.api.AfterEach
    void resetPlayer() {
        PlayerState ps = playerRepo.getSingleton();
        ps.setCurrentQueueId(null);
        playerRepo.save(ps);
    }

    private Long save(String title, String fp) {
        Song s = new Song();
        s.setTitle(title);
        s.setArtist("测试");
        s.setMediaType("KTV_VIDEO");
        s.setFingerprint(fp);
        Song saved = songRepo.save(s);
        SongFile file = new SongFile();
        file.setSongId(saved.getId());
        file.setFilePath("/integration/" + fp + ".mkv");
        file.setFormat("mkv");
        file.setMediaType("KTV_VIDEO");
        file.setProbePending(false);
        file.setFileMtime(OffsetDateTime.now());
        fileRepo.save(file);
        return saved.getId();
    }

    @Test
    void orderAppendsToTail() {
        queueService.order(song1, user1, false);
        queueService.order(song2, user1, false);
        List<QueueItem> q = queueService.waitingList();
        assertThat(q).extracting(QueueItem::getSongId).containsExactly(song1, song2);
    }

    @Test
    void duplicateOrderRejectedUnlessForced() {
        queueService.order(song1, user1, false);
        assertThatThrownBy(() -> queueService.order(song1, user1, false))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SONG_IN_QUEUE");
        // force=true 允许重复
        assertThatCode(() -> queueService.order(song1, user1, true)).doesNotThrowAnyException();
        assertThat(queueService.waitingList()).hasSize(2);
    }

    @Test
    void topInsertsAfterCurrentAndLaterTopperGoesFirst() {
        // 队列：song1, song2, song3
        queueService.order(song1, user1, false);
        QueueItem q2 = queueService.order(song2, user1, false);
        QueueItem q3 = queueService.order(song3, user1, false);

        // 开始播放 → song1 playing
        playbackService.play();

        // 顶 song2 → 应排到 song1(playing) 之后第一位
        queueService.top(q2.getId());
        assertThat(queueService.waitingList()).extracting(QueueItem::getSongId)
                .containsExactly(song2, song3);

        // 再顶 song3 → 后顶者更前
        queueService.top(q3.getId());
        assertThat(queueService.waitingList()).extracting(QueueItem::getSongId)
                .containsExactly(song3, song2);
    }

    @Test
    void playAdvancesAndNextSkips() {
        queueService.order(song1, user1, false);
        queueService.order(song2, user1, false);

        PlayerState ps = playbackService.play();
        assertThat(ps.getState()).isEqualTo("playing");
        Long firstQueueId = ps.getCurrentQueueId();
        assertThat(firstQueueId).isNotNull();

        // 切歌 → 当前标 skipped，推进到 song2
        ps = playbackService.next();
        QueueItem skipped = queueRepo.findById(firstQueueId).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(QueueService.SKIPPED);
        assertThat(ps.getCurrentQueueId()).isNotEqualTo(firstQueueId);

        // 再切 → 队列空 → idle
        ps = playbackService.next();
        assertThat(ps.getState()).isEqualTo("idle");
        assertThat(ps.getCurrentQueueId()).isNull();
    }

    @Test
    void orderingWhileIdleStartsTheFirstWaitingSong() {
        queueService.order(song1, user1, false);

        assertThat(playbackService.startIfIdle()).isTrue();

        PlayerState ps = playerRepo.getSingleton();
        assertThat(ps.getState()).isEqualTo("playing");
        assertThat(ps.getCurrentQueueId()).isNotNull();
        assertThat(queueRepo.findById(ps.getCurrentQueueId()).orElseThrow().getStatus())
                .isEqualTo(QueueService.PLAYING);
        assertThat(playbackService.startIfIdle()).isFalse();
    }

    @Test
    void orderingWhilePausedDoesNotResumeTheCurrentSong() {
        queueService.order(song1, user1, false);
        playbackService.play();
        playbackService.pause();
        Long currentQueueId = playerRepo.getSingleton().getCurrentQueueId();

        queueService.order(song2, user1, false);

        assertThat(playbackService.startIfIdle()).isFalse();
        PlayerState ps = playerRepo.getSingleton();
        assertThat(ps.getState()).isEqualTo("paused");
        assertThat(ps.getCurrentQueueId()).isEqualTo(currentQueueId);
    }

    @Test
    void restartRecoveryKeepsCurrentSongAndResumesFromBeginning() {
        queueService.order(song1, user1, false);
        queueService.order(song2, user1, false);
        playbackService.play();
        playbackService.pause();
        Long currentQueueId = playerRepo.getSingleton().getCurrentQueueId();

        PlayerState recovered = playbackService.recoverAfterRestart();

        assertThat(recovered.getState()).isEqualTo("playing");
        assertThat(recovered.getCurrentQueueId()).isEqualTo(currentQueueId);
        assertThat(queueRepo.findById(currentQueueId).orElseThrow().getStatus())
                .isEqualTo(QueueService.PLAYING);
        assertThat(queueService.waitingList()).extracting(QueueItem::getSongId)
                .containsExactly(song2);
    }

    @Test
    void finishedIncrementsPlayCountAndWritesHistory() {
        queueService.order(song1, user1, false);
        playbackService.play();
        Long queueId = playerRepo.getSingleton().getCurrentQueueId();

        FinishResult first = playbackService.onFinished(queueId);
        FinishResult retry = playbackService.onFinished(queueId);

        Song s = songRepo.findById(song1).orElseThrow();
        assertThat(s.getPlayCount()).isEqualTo(1);
        assertThat(historyRepo.count()).isEqualTo(1);
        assertThat(historyRepo.findAll()).singleElement()
                .extracting(PlayHistory::getQueueId)
                .isEqualTo(queueId);
        assertThat(first.status()).isEqualTo(FinishResult.Status.APPLIED);
        assertThat(retry.status()).isEqualTo(FinishResult.Status.ALREADY_APPLIED);
    }

    @Test
    void volumeAndVocalControls() {
        assertThat(playbackService.setVolume(150).getVolume()).isEqualTo(100); // 上限钳制
        assertThat(playbackService.setVolume(-5).getVolume()).isEqualTo(0);    // 下限
        assertThat(playbackService.setMuted(true).isMuted()).isTrue();
        assertThat(playbackService.setVocalMode("original").getVocalMode()).isEqualTo("original");
        assertThatThrownBy(() -> playbackService.setVocalMode("bad"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void seekPersistsPositionAndAdvancesSeekSequence() {
        queueService.order(song1, user1, false);
        playbackService.play();

        PlayerState first = playbackService.seek(12_345L);
        PlayerState second = playbackService.seek(23_456L);

        assertThat(first.getPositionMs()).isEqualTo(12_345L);
        assertThat(second.getPositionMs()).isEqualTo(23_456L);
        assertThat(second.getSeekSequence()).isEqualTo(first.getSeekSequence() + 1);
    }

    @Test
    void stopPreservesCurrentQueueWithoutMarkingItFinished() {
        queueService.order(song1, user1, false);
        playbackService.play();
        Long currentQueueId = playerRepo.getSingleton().getCurrentQueueId();
        playbackService.seek(12_345L);

        PlayerState stopped = playbackService.stop();

        assertThat(stopped.getState()).isEqualTo("idle");
        assertThat(stopped.getCurrentQueueId()).isEqualTo(currentQueueId);
        assertThat(stopped.getPositionMs()).isEqualTo(12_345L);
        assertThat(queueRepo.findById(currentQueueId).orElseThrow().getStatus())
                .isEqualTo(QueueService.PLAYING);
    }

    @Test
    void stale_finished_report_cannot_advance_a_new_current_song() {
        queueService.order(song1, user1, false);
        queueService.order(song2, user1, false);
        playbackService.play();
        Long currentQueueId = playerRepo.getSingleton().getCurrentQueueId();

        playbackService.onFinished(currentQueueId + 10_000L);

        assertThat(playerRepo.getSingleton().getCurrentQueueId()).isEqualTo(currentQueueId);
        assertThat(queueRepo.findById(currentQueueId).orElseThrow().getStatus())
                .isEqualTo(QueueService.PLAYING);
    }

    @Test
    void swapVocalTracksPersistsForCurrentSong() {
        SongFile file = new SongFile();
        file.setSongId(song1);
        file.setFilePath("/tmp/ktv-test-" + song1 + ".mpg");
        file.setFormat("mpg");
        file.setAudioTracks(2);
        file.setVocalTrackIndex(1);
        file.setFileMtime(OffsetDateTime.now());
        file.setPriority(100);
        fileRepo.save(file);

        queueService.order(song1, user1, false);
        playbackService.play();

        playbackService.swapVocalTracks();
        assertThat(fileRepo.findById(file.getId()).orElseThrow().getVocalTrackIndex()).isEqualTo(0);

        playbackService.swapVocalTracks();
        assertThat(fileRepo.findById(file.getId()).orElseThrow().getVocalTrackIndex()).isEqualTo(1);
    }

    @Test
    void swapVocalChannelsPersistsSemanticLeftRightAssignment() {
        SongFile file = new SongFile();
        file.setSongId(song1);
        file.setFilePath("/tmp/ktv-channel-" + song1 + ".mkv");
        file.setFormat("mkv");
        file.setAudioTracks(1);
        file.setAudioLayout(AudioLayout.DUAL_CHANNEL);
        file.setFileMtime(OffsetDateTime.now());
        file.setPriority(100);
        fileRepo.save(file);

        queueService.order(song1, user1, false);
        playbackService.play();

        playbackService.swapVocalTracks();
        SongFile swapped = fileRepo.findById(file.getId()).orElseThrow();
        assertThat(swapped.getAudioLayout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
        assertThat(swapped.getOriginalChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(swapped.getAccompanimentChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(swapped.getVocalTrackIndex()).isNull();
    }

    @Test
    void clientPlayErrorSkipsQueueWithoutInvalidatingSourceOrRecordingPlay() {
        SongFile file = new SongFile();
        file.setSongId(song1);
        file.setFilePath("/tmp/ktv-error-" + song1 + ".mpg");
        file.setFormat("mpg");
        file.setAudioTracks(2);
        file.setFileMtime(OffsetDateTime.now());
        file.setPriority(100);
        fileRepo.save(file);

        queueService.order(song1, user1, false);
        queueService.order(song2, user1, false);
        playbackService.play();
        Long failedQueueId = playerRepo.getSingleton().getCurrentQueueId();

        PlaybackTransitionResult transition = playbackService.onPlayError(file.getId());

        assertThat(fileRepo.findById(file.getId()).orElseThrow().isValid()).isTrue();
        assertThat(queueRepo.findById(failedQueueId).orElseThrow().getStatus())
                .isEqualTo(QueueService.SKIPPED);
        assertThat(transition.accepted()).isTrue();
        assertThat(transition.state().getCurrentQueueId()).isNotEqualTo(failedQueueId);
        assertThat(songRepo.findById(song1).orElseThrow().getPlayCount()).isZero();
        assertThat(historyRepo.count()).isZero();
    }

    @Test
    void cancelRemovesWaitingItem() {
        QueueItem q1 = queueService.order(song1, user1, false);
        queueService.cancel(q1.getId());
        assertThat(queueService.waitingList()).isEmpty();
    }
}
