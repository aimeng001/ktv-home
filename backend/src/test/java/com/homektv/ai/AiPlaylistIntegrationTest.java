package com.homektv.ai;

import com.homektv.domain.Playlist;
import com.homektv.domain.PlaylistSong;
import com.homektv.domain.Song;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.MediaImportRecord;
import com.homektv.library.MediaClassifier;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.PlaylistRepository;
import com.homektv.repo.PlaylistSongRepository;
import com.homektv.repo.SongRepository;
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
import java.util.Map;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Isolated("Testcontainers JUnit extension does not support parallel test execution")
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
    @Autowired AiAnalysisTaskClaimService aiAnalysisTaskClaimService;
    @Autowired AiAnalysisTaskRecoveryService aiAnalysisTaskRecoveryService;
    @Autowired MediaImportRecordRepository mediaImportRecordRepository;
    @Autowired AiAnalysisFinalizer aiAnalysisFinalizer;

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

    @Test
    void oldAiExecutionCannotWriteAfterPauseResumeClaimsANewerAttempt() {
        String suffix = String.valueOf(System.nanoTime());
        MediaImportRecord record = saveImportRecord(suffix);
        AiAnalysisTask task = new AiAnalysisTask();
        task.setTargetType("IMPORT_RECORD");
        task.setTargetId(record.getId());
        task.setBatchId("aba-" + suffix);
        task.setModelRole("BULK");
        task.setModel("test-model");
        task.setStatus("pending");
        task = aiAnalysisTaskRepository.saveAndFlush(task);
        Long taskId = task.getId();

        int oldAttempt = aiAnalysisTaskClaimService.claim(taskId).orElseThrow();
        task = aiAnalysisTaskRepository.findById(taskId).orElseThrow();
        task.setStatus("paused");
        aiAnalysisTaskRepository.saveAndFlush(task);
        task.setStatus("pending");
        aiAnalysisTaskRepository.saveAndFlush(task);
        int currentAttempt = aiAnalysisTaskClaimService.claim(taskId).orElseThrow();

        AiSongClassification oldResult = importIdentity("旧执行标题", "旧执行歌手");
        AiSongClassification currentResult = importIdentity("新执行标题", "新执行歌手");
        assertThat(oldAttempt).isEqualTo(1);
        assertThat(currentAttempt).isEqualTo(2);
        assertThat(aiAnalysisFinalizer.finishProcessing(taskId, oldAttempt, "old", "{}", "{}",
                "BULK", "test-model", oldResult, true, 0.8, null)).isFalse();
        assertThat(mediaImportRecordRepository.findById(record.getId()).orElseThrow().getParsedTitle())
                .isEqualTo("原导入标题-" + suffix);

        assertThat(aiAnalysisFinalizer.finishProcessing(taskId, currentAttempt, "current", "{}", "{}",
                "BULK", "test-model", currentResult, true, 0.8, null)).isTrue();
        assertThat(mediaImportRecordRepository.findById(record.getId()).orElseThrow().getParsedTitle())
                .isEqualTo("新执行标题");
    }

    @Test
    void startupRecoveryFailsOnlyOrphanedPendingAndProcessingTasks() {
        OffsetDateTime cutoff = OffsetDateTime.now().plusMinutes(1);
        int preexistingRecoverable = aiAnalysisTaskRepository
                .findTop500ByStatusInAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
                        List.of("pending", "processing"), cutoff).size();
        String suffix = String.valueOf(System.nanoTime());
        AiAnalysisTask pending = saveAnalysisTask("pending", suffix + "-pending");
        AiAnalysisTask processing = saveAnalysisTask("processing", suffix + "-processing");
        AiAnalysisTask paused = saveAnalysisTask("paused", suffix + "-paused");
        AiAnalysisTask review = saveAnalysisTask("review", suffix + "-review");
        AiAnalysisTask applied = saveAnalysisTask("auto_applied", suffix + "-applied");

        int recovered = aiAnalysisTaskRecoveryService.recoverOrphanedTasks(cutoff);

        assertThat(recovered).isEqualTo(preexistingRecoverable + 2);
        assertThat(aiAnalysisTaskRepository.findById(pending.getId()).orElseThrow())
                .satisfies(task -> assertThat(task.getStatus()).isEqualTo("failed"))
                .satisfies(task -> assertThat(task.getErrorMessage()).contains("服务重启"));
        assertThat(aiAnalysisTaskRepository.findById(processing.getId()).orElseThrow())
                .satisfies(task -> assertThat(task.getStatus()).isEqualTo("failed"))
                .satisfies(task -> assertThat(task.getErrorMessage()).contains("服务重启"));
        assertThat(aiAnalysisTaskRepository.findById(paused.getId()).orElseThrow().getStatus()).isEqualTo("paused");
        assertThat(aiAnalysisTaskRepository.findById(review.getId()).orElseThrow().getStatus()).isEqualTo("review");
        assertThat(aiAnalysisTaskRepository.findById(applied.getId()).orElseThrow().getStatus())
                .isEqualTo("auto_applied");
    }

    @Test
    void pauseThatCommitsBeforeFinalizationPreventsSongMutation() {
        String suffix = String.valueOf(System.nanoTime());
        Song song = saveSong("暂停前标题-" + suffix, "未知歌手", "pause-first-" + suffix);
        AiAnalysisTask task = processingTask(song.getId(), "pause-first-batch-" + suffix);

        service.pauseRepairBatch(task.getBatchId());

        assertThat(finishWithConfidentClassification(task, "暂停后标题-" + suffix, "新歌手-" + suffix))
                .isFalse();
        assertThat(songRepository.findById(song.getId()).orElseThrow().getTitle())
                .isEqualTo("暂停前标题-" + suffix);
        AiAnalysisTask persistedTask = aiAnalysisTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(persistedTask.getStatus()).isEqualTo("paused");
        assertThat(persistedTask.getResultJson()).isNull();
    }

    @Test
    void finalizationThatCommitsBeforePauseKeepsAppliedResultAndTarget() {
        String suffix = String.valueOf(System.nanoTime());
        Song song = saveSong("提交前标题-" + suffix, "未知歌手", "finalize-first-" + suffix);
        AiAnalysisTask task = processingTask(song.getId(), "finalize-first-batch-" + suffix);
        String expectedTitle = "提交后标题-" + suffix;
        String expectedArtist = "新歌手-" + suffix;

        assertThat(finishWithConfidentClassification(task, expectedTitle, expectedArtist)).isTrue();
        service.pauseRepairBatch(task.getBatchId());

        Song persistedSong = songRepository.findById(song.getId()).orElseThrow();
        assertThat(persistedSong.getTitle()).isEqualTo(expectedTitle);
        assertThat(persistedSong.getArtist()).isEqualTo(expectedArtist);
        assertThat(aiAnalysisTaskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo("auto_applied");
    }

    @Test
    void failedTargetMutationRollsBackTheTaskResultAndSongChangesTogether() {
        String suffix = String.valueOf(System.nanoTime());
        String conflictingTitle = "已存在标题-" + suffix;
        String conflictingArtist = "已存在歌手-" + suffix;
        Song target = saveSong("原始标题-" + suffix, "未知歌手", "rollback-target-" + suffix);
        Song conflict = saveSong(conflictingTitle, conflictingArtist,
                MediaClassifier.fingerprint(conflictingArtist, conflictingTitle, 0));
        AiAnalysisTask task = processingTask(target.getId(), "rollback-batch-" + suffix);

        assertThatThrownBy(() -> finishWithConfidentClassification(task, conflictingTitle, conflictingArtist))
                .hasMessageContaining("重复");

        Song persistedTarget = songRepository.findById(target.getId()).orElseThrow();
        assertThat(persistedTarget.getTitle()).isEqualTo("原始标题-" + suffix);
        assertThat(persistedTarget.getArtist()).isEqualTo("未知歌手");
        AiAnalysisTask persistedTask = aiAnalysisTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(persistedTask.getStatus()).isEqualTo("processing");
        assertThat(persistedTask.getResultJson()).isNull();
        assertThat(songRepository.findById(conflict.getId())).isPresent();
    }

    @Test
    void pauseThatCommitsBeforeFinalizationPreventsImportRecordMutation() {
        String suffix = String.valueOf(System.nanoTime());
        MediaImportRecord record = saveImportRecord(suffix);
        AiAnalysisTask task = processingImportTask(record.getId(), "pause-import-batch-" + suffix);

        service.pauseRepairBatch(task.getBatchId());

        assertThat(finishWithConfidentClassification(task, "导入新标题-" + suffix, "导入新歌手-" + suffix))
                .isFalse();
        MediaImportRecord persistedRecord = mediaImportRecordRepository.findById(record.getId()).orElseThrow();
        assertThat(persistedRecord.getParsedTitle()).isEqualTo("原导入标题-" + suffix);
        assertThat(persistedRecord.getParsedArtist()).isEqualTo("原导入歌手-" + suffix);
        assertThat(aiAnalysisTaskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo("paused");
    }

    @Test
    void importRecordMutationAndTerminalStatusCommitTogether() {
        String suffix = String.valueOf(System.nanoTime());
        MediaImportRecord record = saveImportRecord(suffix);
        AiAnalysisTask task = processingImportTask(record.getId(), "finish-import-batch-" + suffix);
        String expectedTitle = "导入新标题-" + suffix;
        String expectedArtist = "导入新歌手-" + suffix;

        assertThat(finishWithConfidentClassification(task, expectedTitle, expectedArtist)).isTrue();

        MediaImportRecord persistedRecord = mediaImportRecordRepository.findById(record.getId()).orElseThrow();
        assertThat(persistedRecord.getParsedTitle()).isEqualTo(expectedTitle);
        assertThat(persistedRecord.getParsedArtist()).isEqualTo(expectedArtist);
        assertThat(aiAnalysisTaskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo("auto_applied");
    }

    private AiAnalysisTask processingTask(Long songId, String batchId) {
        AiAnalysisTask task = taskFor(songId);
        task.setStatus("processing");
        task.setBatchId(batchId);
        return aiAnalysisTaskRepository.saveAndFlush(task);
    }

    private AiAnalysisTask saveAnalysisTask(String status, String suffix) {
        Song song = saveSong("恢复任务-" + suffix, "artist-" + suffix, "recovery-" + suffix);
        AiAnalysisTask task = taskFor(song.getId());
        task.setStatus(status);
        task.setBatchId("recovery-" + suffix);
        return aiAnalysisTaskRepository.saveAndFlush(task);
    }

    private AiAnalysisTask processingImportTask(Long recordId, String batchId) {
        AiAnalysisTask task = new AiAnalysisTask();
        task.setTargetType("IMPORT_RECORD");
        task.setTargetId(recordId);
        task.setStatus("processing");
        task.setBatchId(batchId);
        task.setModelRole("BULK");
        task.setModel("test-model");
        return aiAnalysisTaskRepository.saveAndFlush(task);
    }

    private MediaImportRecord saveImportRecord(String suffix) {
        MediaImportRecord record = new MediaImportRecord();
        record.setSourcePath("/tmp/ktv-import-test-" + suffix + ".mp4");
        record.setSourceFilename("原始文件-" + suffix + ".mp4");
        record.setSourceMd5("import-test-md5-" + suffix);
        record.setParsedTitle("原导入标题-" + suffix);
        record.setParsedArtist("原导入歌手-" + suffix);
        record.setAction("READY");
        return mediaImportRecordRepository.saveAndFlush(record);
    }

    private boolean finishWithConfidentClassification(AiAnalysisTask task, String title, String artist) {
        AiSongClassification classification = new AiSongClassification(
                title, artist, "国语", "90年代", List.of("流行"), List.of("怀旧"),
                "全年龄", "独唱", List.of(), "集成测试", 0.99,
                0.99, 0.99, 0.99, 0.99, "男歌手", Map.of());
        return aiAnalysisFinalizer.finishProcessing(task.getId(), task.getAttemptCount(), "{}", "{}", "{}",
                "BULK", "test-model", classification, true, 0.90, null);
    }

    private AiSongClassification importIdentity(String title, String artist) {
        return new AiSongClassification(title, artist, "国语", "90年代", List.of("流行"), List.of("怀旧"),
                "全年龄", "独唱", List.of(), "轮次验收", 0.99,
                0.99, 0.99, 0.99, 0.99, "男歌手", Map.of());
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
