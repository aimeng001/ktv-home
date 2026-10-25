package com.homektv.musicsource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.mockito.stubbing.Answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MusicMetadataScrapeWorkerFlowTest {

    @Test
    void stoppedBatchReturnsAndEmptyRunningBatchCompletes() {
        WorkerContext stopped = context(List.of("PAUSED"), List.of(), false, null, List.of(), 0.9, List.of(), 0);
        stopped.worker.process("batch-1");
        assertThat(stopped.updates).isEmpty();
        verifyNoInteractions(stopped.search, stopped.apply);

        WorkerContext missingBatch = context(List.of(), List.of(), false, null, List.of(), 0.9, List.of(), 0);
        missingBatch.worker.process("missing-batch");
        assertThat(missingBatch.updates).isEmpty();
        verifyNoInteractions(missingBatch.search, missingBatch.apply);

        WorkerContext empty = context(List.of("RUNNING"), List.of(), false, null, List.of(), 0.9, List.of(), 0);
        empty.worker.process("batch-2");
        assertThat(empty.updates).anySatisfy(update -> {
            assertThat(update.sql()).contains("SET status='COMPLETED'");
            assertThat(Arrays.asList(update.args())).contains("batch-2");
        });
    }

    @Test
    void deletedSongClaimBecomesAFailure() {
        WorkerContext context = context(List.of("RUNNING"), List.of(List.of(7L)), false, null,
                List.of(), 0.9, List.of(), 0);

        context.worker.process("batch-1");

        assertTerminalStatus(context, "FAILED", "歌曲已删除");
        verifyNoInteractions(context.search, context.apply);
    }

    @Test
    void nullSongIdInClaimedItemBecomesAFailure() {
        WorkerContext context = context(List.of("RUNNING"), List.of(List.of(17L)), true, null,
                List.of(), 0.9, List.of(), 0);

        context.worker.process("batch-null-song");

        assertTerminalStatus(context, "FAILED", "歌曲已删除");
        verifyNoInteractions(context.search, context.apply);
    }

    @Test
    void pausedBatchStopsBeforeProcessingClaimedItem() {
        WorkerContext context = context(List.of("RUNNING", "RUNNING", "PAUSED"),
                List.of(List.of(18L)), true, 58L, List.of(), 0.9, List.of(), 1);

        context.worker.process("batch-paused");

        assertThat(context.updates).hasSize(1);
        assertThat(context.updates.getFirst().sql()).contains("started_at=COALESCE");
        verifyNoInteractions(context.search, context.apply);
    }

    @Test
    void missingAndWeakCandidatesRemainInReview() {
        WorkerContext noCandidate = context(List.of("RUNNING"), List.of(List.of(7L)), true, 31L,
                List.of(), 0.9, List.of(), 0);
        noCandidate.worker.process("batch-1");
        assertTerminalStatus(noCandidate, "REVIEW", "未找到可用的元数据候选");

        WorkerContext weak = context(List.of("RUNNING"), List.of(List.of(8L)), true, 32L,
                List.of(match("weak", 0.72)), 0.9, List.of(1), 0);
        weak.worker.process("batch-2");
        assertTerminalStatus(weak, "REVIEW", "匹配度低于自动写入阈值，等待人工审核");
        verifyNoInteractions(noCandidate.apply, weak.apply);
    }

    @Test
    void appliesStrongMatchesAndReviewsProviderWriteFailures() {
        MusicSourceSearchService.SongMatch strong = match("strong", 0.99);
        WorkerContext success = context(List.of("RUNNING"), List.of(List.of(7L)), true, 41L,
                List.of(strong), 0.9, List.of(1, 1), 0);
        success.worker.process("batch-1");
        verify(success.apply).apply(eq(41L), eq(MusicProvider.QQ), eq("strong"), any());
        assertTerminalStatus(success, "AUTO_APPLIED", null);

        WorkerContext failure = context(List.of("RUNNING"), List.of(List.of(8L)), true, 42L,
                List.of(strong), 0.9, List.of(1, 1), 0);
        when(failure.apply.apply(eq(42L), eq(MusicProvider.QQ), eq("strong"), any()))
                .thenThrow(new IllegalStateException("write failed"));
        failure.worker.process("batch-2");
        assertTerminalStatus(failure, "REVIEW", "自动写入未执行：write failed");

        WorkerContext thresholdEqual = context(List.of("RUNNING"), List.of(List.of(12L)), true, 44L,
                List.of(strong), 0.99, List.of(1, 1), 0);
        thresholdEqual.worker.process("batch-equal-threshold");
        verify(thresholdEqual.apply).apply(eq(44L), eq(MusicProvider.QQ), eq("strong"), any());
        assertTerminalStatus(thresholdEqual, "AUTO_APPLIED", null);
    }

    @Test
    void releasesAClaimWhenItIsLostBeforeApplying() {
        WorkerContext context = context(List.of("RUNNING"), List.of(List.of(9L)), true, 43L,
                List.of(match("strong", 0.99)), 0.9, List.of(1, 0), 0);

        context.worker.process("batch-1");

        assertThat(context.updates).anySatisfy(update -> assertThat(update.sql()).contains("SET status='PENDING'"));
        verifyNoInteractions(context.apply);
    }

    @Test
    void releasesAClaimWhenTheBatchPausesBeforeApplying() {
        WorkerContext context = context(List.of("RUNNING", "RUNNING", "RUNNING", "RUNNING", "PAUSED"),
                List.of(List.of(19L)), true, 45L, List.of(match("strong", 0.99)),
                0.9, List.of(), 1);

        context.worker.process("batch-paused-before-apply");

        assertThat(context.updates).anySatisfy(update -> assertThat(update.sql()).contains("SET status='PENDING'"));
        verify(context.search).matches(45L, false);
        verifyNoInteractions(context.apply);
    }

    @Test
    void releasesAClaimWhenClaimCountIsNull() {
        WorkerContext context = context(List.of("RUNNING"), List.of(List.of(20L)), true, 46L,
                List.of(match("strong", 0.99)), 0.9, List.of(-1), 1);

        context.worker.process("batch-null-claim-count");

        assertThat(context.updates).anySatisfy(update -> assertThat(update.sql()).contains("SET status='PENDING'"));
        verifyNoInteractions(context.apply);
    }

    @Test
    void providerErrorsUseTheExceptionTypeWhenNoMessageExists() {
        WorkerContext context = context(List.of("RUNNING"), List.of(List.of(10L)), true, 51L,
                List.of(), 0.9, List.of(), 0);
        when(context.search.matches(51L, false)).thenThrow(new IllegalStateException());

        context.worker.process("batch-1");

        assertTerminalStatus(context, "FAILED", "IllegalStateException");
    }

    @Test
    void providerErrorsFallBackToCauseWhenMessageIsBlank() {
        WorkerContext context = context(List.of("RUNNING"), List.of(List.of(21L)), true, 61L,
                List.of(), 0.9, List.of(), 0);
        when(context.search.matches(61L, false)).thenThrow(
                new IllegalStateException(" ", new IllegalArgumentException("provider unavailable")));

        context.worker.process("batch-cause-message");

        assertTerminalStatus(context, "FAILED", "provider unavailable");
    }

    @Test
    void providerErrorsWithOnlyWhitespaceFallBackToExceptionType() {
        WorkerContext context = context(List.of("RUNNING"), List.of(List.of(22L)), true, 62L,
                List.of(), 0.9, List.of(), 0);
        when(context.search.matches(62L, false)).thenThrow(new IllegalStateException("   "));

        context.worker.process("batch-whitespace-message");

        assertTerminalStatus(context, "FAILED", "IllegalStateException");
    }

    @Test
    void leavesBatchUnchangedWhenActiveCountIsUnavailable() {
        WorkerContext context = context(List.of("RUNNING"), List.of(), false, null,
                List.of(), 0.9, List.of(), null);

        context.worker.process("batch-active-count-null");

        assertThat(context.updates).hasSize(1);
        assertThat(context.updates.getFirst().sql()).contains("started_at=COALESCE");
    }

    private static WorkerContext context(List<String> statuses, List<List<Long>> claims,
                                         boolean targetFound, Long songId,
                                         List<MusicSourceSearchService.SongMatch> matches,
                                         double threshold, List<Integer> claimCounts, Integer activeCount) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MusicSourceSearchService search = mock(MusicSourceSearchService.class);
        MusicMetadataApplyService apply = mock(MusicMetadataApplyService.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getConfig()).thenReturn(new MusicSourceConfig(true, Set.of(MusicProvider.QQ),
                20, 5, 6, 2, 1500, 0.95));
        if (songId != null) when(search.matches(songId, false)).thenReturn(matches);

        Deque<List<Long>> pendingClaims = new ConcurrentLinkedDeque<>(claims);
        Deque<Integer> claimResults = new ConcurrentLinkedDeque<>(claimCounts);
        Deque<String> batchStatuses = new ConcurrentLinkedDeque<>(statuses);
        CopyOnWriteArrayList<UpdateCall> updates = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT status FROM music_metadata_scrape_batches")) {
                if (batchStatuses.size() > 1) return List.of(batchStatuses.removeFirst());
                return batchStatuses.isEmpty() ? List.of() : List.of(batchStatuses.getFirst());
            }
            if (sql.contains("WITH candidates")) return pendingClaims.isEmpty() ? List.of() : pendingClaims.removeFirst();
            throw new AssertionError("Unexpected RowMapper query: " + sql);
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        doAnswer(invocation -> {
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.next()).thenReturn(targetFound);
            when(row.getObject(1)).thenReturn(songId);
            return extractor.extractData(row);
        }).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("auto_apply_threshold")) return threshold;
            if (sql.contains("WHERE id=? AND batch_id=? AND status='PROCESSING'")) {
                if (claimResults.isEmpty()) return 0;
                Integer result = claimResults.removeFirst();
                return result != null && result < 0 ? null : result;
            }
            if (sql.contains("status IN ('PENDING','PROCESSING')")) return activeCount;
            throw new AssertionError("Unexpected queryForObject: " + sql);
        }).when(jdbc).queryForObject(anyString(), any(Class.class), any(Object[].class));
        Answer<Integer> recordUpdate = invocation -> {
            Object[] rawArguments = invocation.getArguments();
            Object[] bindArguments = rawArguments.length == 2 && rawArguments[1] instanceof Object[] values
                    ? values
                    : Arrays.copyOfRange(rawArguments, 1, rawArguments.length);
            updates.add(new UpdateCall(invocation.getArgument(0), bindArguments));
            return 1;
        };
        doAnswer(recordUpdate).when(jdbc).update(anyString(), any(Object.class));
        doAnswer(recordUpdate).when(jdbc).update(anyString(), any(Object[].class));

        MusicMetadataScrapeWorker worker = new MusicMetadataScrapeWorker(
                jdbc, search, apply, config, new ObjectMapper().registerModule(new JavaTimeModule()));
        return new WorkerContext(worker, search, apply, updates);
    }

    private static MusicSourceSearchService.SongMatch match(String externalId, double score) {
        ExternalTrack track = new ExternalTrack(MusicProvider.QQ, externalId, "晴天", List.of("周杰伦"),
                null, 240_000, null, List.of(), null, "AVAILABLE", null);
        return new MusicSourceSearchService.SongMatch(track, score);
    }

    private static void assertTerminalStatus(WorkerContext context, String status, String message) {
        assertThat(context.updates).anySatisfy(update -> {
            assertThat(update.sql()).contains("SET status=?,error_message=?");
            assertThat(Arrays.asList(update.args())).contains(status);
            if (message != null) assertThat(Arrays.asList(update.args())).contains(message);
        });
    }

    private record WorkerContext(MusicMetadataScrapeWorker worker, MusicSourceSearchService search,
                                 MusicMetadataApplyService apply, List<UpdateCall> updates) { }

    private record UpdateCall(String sql, Object[] args) { }
}
