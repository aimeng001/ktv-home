package com.homektv.ai;

import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.MediaImportRecord;
import com.homektv.domain.Song;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAnalysisFinalizerTest {
    private static final long TASK_ID = 71L;
    private static final long IMPORT_ID = 92L;
    private static final long SONG_ID = 38L;

    @Test
    void missingTaskIsNotFinalized() {
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        AiAnalysisFinalizer finalizer = new AiAnalysisFinalizer(tasks, imports, mock(AiClassificationApplier.class));
        when(tasks.findByIdForUpdate(TASK_ID)).thenReturn(Optional.empty());

        assertThat(finish(finalizer, null, true, 0.8, "审核原因")).isFalse();

        verify(imports, never()).findById(any());
    }

    @Test
    void taskOutsideProcessingStateIsNotFinalized() {
        AiAnalysisTask task = task("IMPORT_RECORD");
        task.setStatus("paused");
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        when(tasks.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        AiAnalysisFinalizer finalizer = new AiAnalysisFinalizer(tasks, imports, mock(AiClassificationApplier.class));

        assertThat(finish(finalizer, classification("歌名", "歌手", 0.99, 0.99, null), true, 0.8, "审核原因"))
                .isFalse();

        assertThat(task.getResultJson()).isNull();
        verify(imports, never()).findById(any());
    }

    @Test
    void processingResultWithoutAutoApplyIsSavedForReview() {
        AiAnalysisTask task = task("IMPORT_RECORD");
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        when(tasks.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        AiAnalysisFinalizer finalizer = new AiAnalysisFinalizer(tasks, imports, mock(AiClassificationApplier.class));

        assertThat(finish(finalizer, null, false, 0.8, "需要人工核对")).isTrue();

        assertThat(task.getResultJson()).isEqualTo("{\"result\":true}");
        assertThat(task.getFieldConfidence()).isEqualTo("{\"title\":0.99}");
        assertThat(task.getEvidence()).isEqualTo("{\"source\":\"model\"}");
        assertThat(task.getModelRole()).isEqualTo("BULK");
        assertThat(task.getModel()).isEqualTo("local-test-model");
        assertThat(task.getStatus()).isEqualTo("review");
        assertThat(task.getErrorMessage()).isEqualTo("需要人工核对");
        verify(imports, never()).findById(any());
    }

    @ParameterizedTest(name = "incomplete or low-confidence identity stays in review: {0}")
    @MethodSource("unconfidentIdentities")
    void importRecordIdentityMustMeetEveryConfidenceCondition(String description, AiSongClassification result) {
        AiAnalysisTask task = task("IMPORT_RECORD");
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        when(tasks.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        AiAnalysisFinalizer finalizer = new AiAnalysisFinalizer(tasks, imports, mock(AiClassificationApplier.class));

        assertThat(finish(finalizer, result, true, 0.8, "人工复核")).isTrue();

        assertThat(task.getStatus()).isEqualTo("review");
        verify(imports, never()).findById(any());
    }

    static Stream<Arguments> unconfidentIdentities() {
        return Stream.of(
                Arguments.of("null title", classification(null, "歌手", 0.99, 0.99, null)),
                Arguments.of("blank title", classification("  ", "歌手", 0.99, 0.99, null)),
                Arguments.of("null artist", classification("歌名", null, 0.99, 0.99, null)),
                Arguments.of("blank artist", classification("歌名", "  ", 0.99, 0.99, null)),
                Arguments.of("title confidence below threshold", classification("歌名", "歌手", 0.79, 0.99, null)),
                Arguments.of("artist confidence below threshold", classification("歌名", "歌手", 0.99, 0.79, null))
        );
    }

    @ParameterizedTest(name = "confident import identity stores AI reason {0}")
    @MethodSource("classificationReasons")
    void confidentImportIdentityIsTrimmedAndSaved(String classificationReason, String expectedReason) {
        AiAnalysisTask task = task("IMPORT_RECORD");
        MediaImportRecord record = new MediaImportRecord();
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        when(tasks.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(imports.findById(IMPORT_ID)).thenReturn(Optional.of(record));
        AiAnalysisFinalizer finalizer = new AiAnalysisFinalizer(tasks, imports, mock(AiClassificationApplier.class));

        assertThat(finish(finalizer, classification("  歌名  ", "  歌手  ", 0.8, 0.8, classificationReason),
                true, 0.8, "人工审核说明")).isTrue();

        assertThat(task.getStatus()).isEqualTo("auto_applied");
        assertThat(record.getParsedTitle()).isEqualTo("歌名");
        assertThat(record.getParsedArtist()).isEqualTo("歌手");
        assertThat(record.getReason()).isEqualTo(expectedReason);
        verify(imports).save(record);
    }

    static Stream<Arguments> classificationReasons() {
        return Stream.of(
                Arguments.of(null, "AI 已优化文件身份："),
                Arguments.of("人工原因", "AI 已优化文件身份：人工原因")
        );
    }

    @Test
    void missingImportRecordFailsInsteadOfMarkingTaskApplied() {
        AiAnalysisTask task = task("IMPORT_RECORD");
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        when(tasks.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(imports.findById(IMPORT_ID)).thenReturn(Optional.empty());
        AiAnalysisFinalizer finalizer = new AiAnalysisFinalizer(tasks, imports, mock(AiClassificationApplier.class));

        assertThatThrownBy(() -> finish(finalizer, classification("歌名", "歌手", 0.9, 0.9, null),
                true, 0.8, "人工复核"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("导入记录不存在");
    }

    @Test
    void songTargetUsesTheClassificationResultToChooseAppliedOrReviewState() {
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        SongRepository songs = mock(SongRepository.class);
        AiConfigService config = mock(AiConfigService.class);
        Song song = new Song();
        song.setId(SONG_ID);
        song.setTitle("原歌名");
        song.setArtist("原歌手");
        song.setLanguage("英语");
        song.setStatus("ok");
        AiAnalysisTask task = task("SONG");
        when(tasks.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(songs.findById(SONG_ID)).thenReturn(Optional.of(song));
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(songs.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(config.resolve()).thenReturn(defaultConfig());
        AiClassificationApplier applier = new AiClassificationApplier(songs, config);
        AiAnalysisFinalizer finalizer = new AiAnalysisFinalizer(tasks, imports, applier);

        assertThat(finish(finalizer, songLanguage("国语", 0.99), true, 0.8, "审核原因")).isTrue();

        assertThat(song.getLanguage()).isEqualTo("国语");
        assertThat(task.getStatus()).isEqualTo("auto_applied");
        verify(songs).save(song);
    }

    private static AiAnalysisTask task(String targetType) {
        AiAnalysisTask task = new AiAnalysisTask();
        task.setStatus("processing");
        task.setTargetType(targetType);
        task.setTargetId(IMPORT_ID);
        task.setSongId(SONG_ID);
        return task;
    }

    private static boolean finish(AiAnalysisFinalizer finalizer, AiSongClassification classification,
                                  boolean autoApply, double threshold, String reviewMessage) {
        return finalizer.finishProcessing(TASK_ID, "{\"result\":true}", "{\"title\":0.99}",
                "{\"source\":\"model\"}", "BULK", "local-test-model", classification,
                autoApply, threshold, reviewMessage);
    }

    private static AiSongClassification classification(String title, String artist, double titleConfidence,
                                                       double artistConfidence, String reason) {
        return new AiSongClassification(title, artist, null, null, List.of(), List.of(), null, null, List.of(),
                reason, 0.99, titleConfidence, artistConfidence, 0.0, 0.0, null, Map.of());
    }

    private static AiSongClassification songLanguage(String language, double confidence) {
        return new AiSongClassification(null, null, language, null, List.of(), List.of(), null, null, List.of(),
                null, 0.99, 0.0, 0.0, confidence, 0.0, "未知", Map.of());
    }

    private static AiConfigService.ResolvedConfig defaultConfig() {
        return new AiConfigService.ResolvedConfig(true, "https://ai.example.test", "model", "",
                30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 1, 1, "key");
    }
}
