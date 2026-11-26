package com.homektv.library;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistDirectoryProjectionServiceTest {

    @Test
    void concurrentRefreshesMustBeSerializedToAvoidDuplicateProjectionRows() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicBoolean firstUpdate = new AtomicBoolean(true);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                if (firstUpdate.compareAndSet(true, false)) {
                    firstEntered.countDown();
                    release.await(2, TimeUnit.SECONDS);
                } else {
                    secondEntered.countDown();
                }
                return 1;
            } finally {
                inFlight.decrementAndGet();
            }
        }).when(jdbc).update(anyString(), any(Object[].class));

        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(service::refresh);
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(service::refresh);

            assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(maxInFlight.get()).isEqualTo(1);

            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertThat(maxInFlight.get()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void refreshQueryMustUseSingleCreditGenderAndPreserveManualProfiles() {
        assertThat(ArtistDirectoryProjectionService.REFRESH_SQL)
                .contains("credit_count = 1")
                .contains("count(DISTINCT artist_gender) = 1")
                .contains("gender_status = 'MANUAL'")
                .contains("Various Artists")
                .contains("UNATTRIBUTED");
    }

    @Test
    void projectionMigrationMustProvideLookupIndexes() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V48__artist_directory_projection.sql");
        assertThat(Files.readString(migration))
                .contains("CREATE TABLE artist_directory_stats")
                .contains("idx_artist_directory_stats_lookup")
                .contains("artist_directory_projection_state");
    }

    @Test
    void pageIsUnavailableUntilTheProjectionHasBeenPublished() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        assertThat(service.page("女歌手", "A", 0, 20)).isEmpty();

        verify(jdbc).queryForObject(contains("SELECT EXISTS"), eq(Boolean.class), any(Object[].class));
        verify(jdbc, never()).queryForObject(contains("SELECT COUNT(*)"), eq(Long.class), any(Object[].class));
    }

    @Test
    void pageTrimsFiltersAndClampsNegativePageAndOversizedPageLength() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        stubSingleArtistRow(jdbc);
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        var result = service.page(" 女歌手 ", " 热门 ", -3, 500).orElseThrow();

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.rows()).containsExactly(new ArtistDirectoryProjectionService.ArtistRow(
                "lee-hom", "王力宏", "W", "男歌手", 42L, true, "PERSON", "/data/artist/lee.webp"));
        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("LIMIT ? OFFSET ?"), any(RowMapper.class), arguments.capture());
        assertThat(arguments.getValue()).containsExactly("女歌手", 101, 0);
        verify(jdbc, never()).queryForObject(contains("SELECT COUNT(*)"), eq(Long.class), any(Object[].class));
    }

    @Test
    void pageNormalizesNullFiltersAndNullTotalAndClampsPageLengthToOne() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Long.class), any(Object[].class))).thenReturn(null);
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        var result = service.page(null, null, 2, 0).orElseThrow();

        assertThat(result.total()).isZero();
        assertThat(result.rows()).isEmpty();
        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("LIMIT ? OFFSET ?"), any(RowMapper.class), arguments.capture());
        assertThat(arguments.getValue()).containsExactly(2, 2);
    }

    @Test
    void initialsAreUnavailableBeforeRefreshAndNormalizeGenderAfterRefresh() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(false, true);
        doReturn(List.of("A", "W")).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        assertThat(service.initials("女歌手")).isEmpty();
        assertThat(service.initials(" 女歌手 ")).contains(List.of("A", "W"));
        // Cached hit on second invocation does not re-query DB
        assertThat(service.initials("女歌手")).contains(List.of("A", "W"));

        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("SELECT DISTINCT directory.initial"), any(RowMapper.class), arguments.capture());
        assertThat(arguments.getValue()).containsExactly("女歌手");
    }

    @Test
    void hashInitialQueriesNonAzBucketsAndInitialsCollapseNonAzToTrailingHash() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        stubSingleArtistRow(jdbc);
        doReturn(List.of("B", "a", "あ", "А", "9", "#")).when(jdbc)
                .query(contains("SELECT DISTINCT directory.initial"), any(RowMapper.class), any(Object[].class));

        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);
        assertThat(service.initials("")).contains(List.of("A", "B", "#"));

        service.page("", "#", "", 0, 20);
        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("directory.initial NOT IN ('A'"), any(RowMapper.class), arguments.capture());
        assertThat(arguments.getValue()).containsExactly(21, 0);
    }

    @Test
    void refreshAfterCommitDoesNotStartUntilTheTransactionCommits() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CountDownLatch refreshComplete = stubRefreshCompletion(jdbc);
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.requestRefreshAfterCommit();
            verify(jdbc, never()).update(anyString());
            verify(jdbc, never()).update(anyString(), any(Object[].class));

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            assertThat(refreshComplete.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            service.shutdown();
        }
    }

    @Test
    void refreshAfterCommitStartsImmediatelyWhenNoTransactionIsActive() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CountDownLatch refreshComplete = stubRefreshCompletion(jdbc);
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        try {
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            service.requestRefreshAfterCommit();
            assertThat(refreshComplete.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void refreshUsesTheConfiguredTransactionManager() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc, transactionManager);

        service.refresh();

        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).commit(status);
        verify(jdbc).update(ArtistDirectoryProjectionService.REFRESH_SQL);
        verify(jdbc).update(ArtistDirectoryProjectionService.PRUNE_STALE_SQL);
    }

    @Test
    void failedBackgroundRefreshCanBeRetriedOnTheSameService() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicBoolean failFirstUpdate = new AtomicBoolean(true);
        AtomicInteger successfulUpdates = new AtomicInteger();
        CountDownLatch failedAttempt = new CountDownLatch(1);
        CountDownLatch retryComplete = new CountDownLatch(1);
        org.mockito.stubbing.Answer<Integer> update = invocation -> {
            if (failFirstUpdate.compareAndSet(true, false)) {
                failedAttempt.countDown();
                throw new IllegalStateException("simulated database outage");
            }
            if (successfulUpdates.incrementAndGet() == 3) retryComplete.countDown();
            return 1;
        };
        doAnswer(update).when(jdbc).update(anyString());
        doAnswer(update).when(jdbc).update(anyString(), any(Object[].class));
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        try {
            service.requestRefresh();
            assertThat(failedAttempt.await(1, TimeUnit.SECONDS)).isTrue();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (retryComplete.getCount() > 0 && System.nanoTime() < deadline) {
                service.requestRefresh();
                retryComplete.await(10, TimeUnit.MILLISECONDS);
            }

            assertThat(retryComplete.getCount()).isZero();
            assertThat(successfulUpdates.get()).isGreaterThanOrEqualTo(3);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void requestArrivingAfterRefreshLoopDrainsIsRescheduledDuringFinalCleanup() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CountDownLatch beforeFinalCleanup = new CountDownLatch(1);
        CountDownLatch continueFinalCleanup = new CountDownLatch(1);
        CountDownLatch secondWaveComplete = new CountDownLatch(1);
        AtomicBoolean pauseFirstRun = new AtomicBoolean(true);
        AtomicInteger updates = new AtomicInteger();
        org.mockito.stubbing.Answer<Integer> update = invocation -> {
            if (updates.incrementAndGet() == 6) secondWaveComplete.countDown();
            return 1;
        };
        doAnswer(update).when(jdbc).update(anyString());
        doAnswer(update).when(jdbc).update(anyString(), any(Object[].class));
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc, null, () -> {
            if (!pauseFirstRun.compareAndSet(true, false)) return;
            beforeFinalCleanup.countDown();
            try {
                continueFinalCleanup.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            service.requestRefresh();
            assertThat(beforeFinalCleanup.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(updates.get()).isEqualTo(3);

            service.requestRefresh();
            assertThat(updates.get()).isEqualTo(3);

            continueFinalCleanup.countDown();
            assertThat(secondWaveComplete.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(updates.get()).isEqualTo(6);
        } finally {
            continueFinalCleanup.countDown();
            service.shutdown();
        }
    }

    @Test
    void refreshRequestsDuringAnActiveRefreshAreCoalescedIntoOneFollowUpWave() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CountDownLatch firstUpdateEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstUpdate = new CountDownLatch(1);
        CountDownLatch secondWaveComplete = new CountDownLatch(1);
        AtomicInteger updates = new AtomicInteger();
        org.mockito.stubbing.Answer<Integer> update = invocation -> {
            int count = updates.incrementAndGet();
            if (count == 1) {
                firstUpdateEntered.countDown();
                releaseFirstUpdate.await(2, TimeUnit.SECONDS);
            }
            if (count == 6) secondWaveComplete.countDown();
            return 1;
        };
        doAnswer(update).when(jdbc).update(anyString());
        doAnswer(update).when(jdbc).update(anyString(), any(Object[].class));
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        try {
            service.requestRefresh();
            assertThat(firstUpdateEntered.await(1, TimeUnit.SECONDS)).isTrue();
            service.requestRefresh();
            service.requestRefresh();
            assertThat(updates.get()).isEqualTo(1);

            releaseFirstUpdate.countDown();
            assertThat(secondWaveComplete.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(updates.get()).isEqualTo(6);
        } finally {
            releaseFirstUpdate.countDown();
            service.shutdown();
        }
    }

    private static void stubSingleArtistRow(JdbcTemplate jdbc) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<ArtistDirectoryProjectionService.ArtistRow> mapper = invocation.getArgument(1);
            java.sql.ResultSet resultSet = mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(resultSet.getString("artist_key")).thenReturn("lee-hom");
            org.mockito.Mockito.when(resultSet.getString("name")).thenReturn("王力宏");
            org.mockito.Mockito.when(resultSet.getString("initial")).thenReturn("W");
            org.mockito.Mockito.when(resultSet.getString("gender")).thenReturn("男歌手");
            org.mockito.Mockito.when(resultSet.getLong("song_count")).thenReturn(42L);
            org.mockito.Mockito.when(resultSet.getBoolean("reviewed")).thenReturn(true);
            org.mockito.Mockito.when(resultSet.getString("artist_kind")).thenReturn("PERSON");
            org.mockito.Mockito.when(resultSet.getString("avatar_path")).thenReturn("/data/artist/lee.webp");
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private static CountDownLatch stubRefreshCompletion(JdbcTemplate jdbc) {
        CountDownLatch refreshComplete = new CountDownLatch(1);
        AtomicInteger updates = new AtomicInteger();
        org.mockito.stubbing.Answer<Integer> update = invocation -> {
            if (updates.incrementAndGet() == 3) refreshComplete.countDown();
            return 1;
        };
        doAnswer(update).when(jdbc).update(anyString());
        doAnswer(update).when(jdbc).update(anyString(), any(Object[].class));
        return refreshComplete;
    }

    @Test
    void adminPageSkipsQueryUntilProjectionReadyAndShortCircuitsZeroTotal() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(false, true);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        assertThat(service.adminPage("", "", null, 0, 50)).isEmpty();
        var empty = service.adminPage("周杰伦", "男歌手", false, 0, 50).orElseThrow();

        assertThat(empty.total()).isZero();
        assertThat(empty.rows()).isEmpty();
        verify(jdbc, never()).query(contains("ORDER BY song_count DESC"), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void adminPageFiltersAndPagesMaterializedDirectory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Long.class), any(Object[].class))).thenReturn(2L);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<ArtistDirectoryProjectionService.AdminArtistRow> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("artist_key")).thenReturn("zhoujielun");
            when(resultSet.getString("name")).thenReturn("周杰伦");
            when(resultSet.getString("gender")).thenReturn("男歌手");
            when(resultSet.getLong("song_count")).thenReturn(128L);
            when(resultSet.getBoolean("reviewed")).thenReturn(true);
            when(resultSet.getString("artist_kind")).thenReturn("PERSON");
            when(resultSet.getString("avatar_path")).thenReturn("artist-covers/zjl.jpg");
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbc).query(contains("ORDER BY song_count DESC"), any(RowMapper.class), any(Object[].class));

        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);
        var result = service.adminPage(" zjl ", " 男歌手 ", true, 1, 25).orElseThrow();

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.rows()).containsExactly(new ArtistDirectoryProjectionService.AdminArtistRow(
                "zhoujielun", "周杰伦", "男歌手", 128L, true, "PERSON", "artist-covers/zjl.jpg"));
        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("ORDER BY song_count DESC"), any(RowMapper.class), arguments.capture());
        assertThat(arguments.getValue()).containsExactly("男歌手", true, "zjl", "zjl", "zjl", "zjl", 25, 25);
    }

    @Test
    void updateGenderAfterCommitUpdatesProjectionRowOrFallsBackToRefresh() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE artist_directory_stats SET gender=?"),
                any(), any(), any())).thenReturn(1, 0);
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        service.updateGenderAfterCommit("zhoujielun", "男歌手", true);
        verify(jdbc).update(contains("UPDATE artist_directory_stats SET gender=?"),
                eq("男歌手"), eq(true), eq("zhoujielun"));
    }

    @Test
    void onApplicationReadyRefreshesWhenNotReady() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        CountDownLatch refreshComplete = stubRefreshCompletion(jdbc);
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(
                jdbc, null, refreshComplete::countDown);

        service.onApplicationReady();
        assertThat(refreshComplete.await(3, TimeUnit.SECONDS)).isTrue();
        verify(jdbc).update(ArtistDirectoryProjectionService.REFRESH_SQL);
        verify(jdbc).update(ArtistDirectoryProjectionService.PRUNE_STALE_SQL);
    }

    @Test
    void onApplicationReadySkipsWhenAlreadyReady() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        service.onApplicationReady();
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void pageEscapesLikeWildcardsAndSkipsPinyinBranchesForCjkKeyword() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);

        service.page("", "", "", "周%_杰\\伦", 0, 30);

        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var argsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("ESCAPE '\\'")
                .doesNotContain("artist_init")
                .doesNotContain("artist_py");
        assertThat(argsCaptor.getValue()).containsExactly("周\\%\\_杰\\\\伦", 31, 0);
    }
}
