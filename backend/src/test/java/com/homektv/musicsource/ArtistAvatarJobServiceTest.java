package com.homektv.musicsource;

import com.homektv.library.ArtistAvatarJobService;
import com.homektv.library.ArtistAvatarWorker;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class ArtistAvatarJobServiceTest {

    @Test
    void enqueuesOnlyProvidersEnabledByTheCurrentConfiguration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenReturn(new int[][]{{1}});

        new ArtistAvatarJobService(jdbc, worker, config).enqueueProfiles(Set.of("周杰伦"));

        verify(jdbc).batchUpdate(anyString(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void enqueuesManyArtistProviderPairsWithOneBoundedBatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ, MusicProvider.NETEASE), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenReturn(new int[][]{{1, 1, 1, 1}});

        new ArtistAvatarJobService(jdbc, worker, config)
                .enqueueProfiles(Set.of("周杰伦", "林俊杰"));

        verify(jdbc).batchUpdate(anyString(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void ordinaryScanEnqueueDoesNotReopenTerminalJobs() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(worker.pendingKeys()).thenReturn(java.util.List.of("zhoujielun"));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenReturn(new int[][]{{1}});

        new ArtistAvatarJobService(jdbc, worker, config).enqueuePendingProfiles();

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sql.capture(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
        assertThat(sql.getValue()).doesNotContain("status = CASE")
                .doesNotContain("attempts=CASE")
                .contains("DO NOTHING");
    }

    @Test
    void ignoresEmptyAndPlaceholderInputsAndKeepsScanningWhenConfigurationStorageIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config);

        service.enqueueProfiles(null);
        service.enqueueProfiles(List.of());
        service.enqueueProfiles(List.of("", "群星", "未知歌手"));
        verifyNoInteractions(config, jdbc, worker);

        when(config.getConfig()).thenThrow(new DataAccessResourceFailureException("configuration table unavailable"));
        service.enqueueProfiles(List.of("周杰伦"));

        verify(config).getConfig();
        verifyNoInteractions(jdbc, worker);
    }

    @Test
    void ignoresPendingCollectionsWithoutAnyUsableArtistKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(worker.pendingKeys()).thenReturn(null, Arrays.asList(null, "  ", "\t"));

        new ArtistAvatarJobService(jdbc, worker, config).enqueuePendingProfiles();
        new ArtistAvatarJobService(jdbc, worker, config).enqueuePendingProfiles();

        verify(worker, org.mockito.Mockito.times(2)).pendingKeys();
        verifyNoInteractions(config, jdbc);
    }

    @Test
    void doesNotCreateJobsWhenTheCurrentConfigurationHasNoUsableProviders() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getConfig()).thenReturn(
                null,
                new MusicSourceConfig(false, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95),
                new MusicSourceConfig(true, null, 20, 5, 6, 1, 1500, 0.95),
                new MusicSourceConfig(true, Set.of(), 20, 5, 6, 1, 1500, 0.95));
        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config);

        for (int index = 0; index < 4; index++) service.enqueueProfiles(List.of("周杰伦"));

        verify(config, org.mockito.Mockito.times(4)).getConfig();
        verifyNoInteractions(jdbc, worker);
    }

    @Test
    void resetAndEnqueueReportsDisabledProvidersAndReopensTerminalJobsWhenEnabled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(worker.unresolvedKeys()).thenReturn(List.of("zhoujielun"));
        when(config.getConfig()).thenReturn(
                null,
                new MusicSourceConfig(true, null, 20, 5, 6, 1, 1500, 0.95),
                new MusicSourceConfig(true, Set.of(), 20, 5, 6, 1, 1500, 0.95),
                new MusicSourceConfig(false, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95),
                new MusicSourceConfig(true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class))).thenReturn(new int[][]{{1}});
        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config);

        for (int index = 0; index < 4; index++) {
            Map<String, Object> unavailable = service.resetAndEnqueueMissingProfiles();
            assertThat(unavailable).containsEntry("queued", 0).containsEntry("reason", "音乐元数据服务未启用");
        }
        verify(jdbc, never()).batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class));

        Map<String, Object> enabled = service.resetAndEnqueueMissingProfiles();

        assertThat(enabled).containsEntry("queued", 1).containsKey("nextWorkAt");
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sql.capture(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
        assertThat(sql.getValue()).contains("status = CASE", "'FAILED', 'SKIPPED', 'REVIEW'")
                .contains("DO UPDATE SET");
        verify(worker).trigger();
    }

    @Test
    void keepsAvatarEnqueueNonBlockingWhenTheJobTableIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("avatar job table unavailable"));

        new ArtistAvatarJobService(jdbc, worker, config).enqueueProfiles(List.of("周杰伦"));

        verify(jdbc).batchUpdate(anyString(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
        verify(worker, never()).trigger();
    }

    @Test
    void defersPendingProfileEnqueueUntilTheActiveTransactionCommits() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(worker.pendingKeys()).thenReturn(List.of("zhoujielun"));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class))).thenReturn(new int[][]{{1}});
        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            service.enqueuePendingProfilesAfterCommit();
            verifyNoInteractions(worker, config, jdbc);

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(worker).pendingKeys();
            verify(jdbc).batchUpdate(anyString(), any(Collection.class), eq(500),
                    any(ParameterizedPreparedStatementSetter.class));
            verify(worker).trigger();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void immediatelyEnqueuesPendingProfilesWhenNoTransactionIsActive() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(worker.pendingKeys()).thenReturn(List.of("zhoujielun"));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class))).thenReturn(new int[][]{{1}});

        new ArtistAvatarJobService(jdbc, worker, config).enqueuePendingProfilesAfterCommit();

        verify(worker).pendingKeys();
        verify(jdbc).batchUpdate(anyString(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
    }

    @Test
    void immediatelyEnqueuesWhenTransactionSynchronizationIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(worker.pendingKeys()).thenReturn(List.of("zhoujielun"));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class))).thenReturn(new int[][]{{1}});
        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            service.enqueuePendingProfilesAfterCommit();
            verify(worker).pendingKeys();
            verify(jdbc).batchUpdate(anyString(), any(Collection.class), eq(500),
                    any(ParameterizedPreparedStatementSetter.class));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void doesNotTriggerWorkerWhenBatchInsertAddsNoRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenReturn(new int[0][])
                .thenReturn(null);
        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config);

        service.enqueueProfiles(List.of("周杰伦"));
        service.enqueueProfiles(List.of("林俊杰"));

        verify(jdbc, org.mockito.Mockito.times(2)).batchUpdate(anyString(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
        verify(worker, never()).trigger();
    }

    @Test
    void trimsAndDeduplicatesPendingKeysBeforeCreatingProviderJobs() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(worker.pendingKeys()).thenReturn(Arrays.asList(null, "  ", " zhoujielun ", "zhoujielun"));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class))).thenReturn(new int[][]{{1}});
        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config);
        org.mockito.ArgumentCaptor<Collection> jobs = org.mockito.ArgumentCaptor.forClass(Collection.class);
        org.mockito.ArgumentCaptor<ParameterizedPreparedStatementSetter> binder =
                org.mockito.ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);

        service.enqueuePendingProfiles();

        verify(jdbc).batchUpdate(anyString(), jobs.capture(), eq(500), binder.capture());
        assertThat(jobs.getValue()).hasSize(1);
        java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
        binder.getValue().setValues(statement, jobs.getValue().iterator().next());
        org.mockito.Mockito.verify(statement).setString(1, "zhoujielun");
        org.mockito.Mockito.verify(statement).setString(2, "QQ");
        verify(worker).trigger();
    }

    @Test
    void coalescesRequestsDuringAnUnresolvedRefreshAndQueuesOneFollowUp() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        CountDownLatch firstRefreshStarted = new CountDownLatch(1);
        CountDownLatch allowFirstRefreshToFinish = new CountDownLatch(1);
        AtomicInteger refreshCount = new AtomicInteger();
        when(worker.unresolvedKeys()).thenAnswer(invocation -> {
            if (refreshCount.incrementAndGet() == 1) {
                firstRefreshStarted.countDown();
                if (!allowFirstRefreshToFinish.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("first refresh was not released");
                }
            }
            return List.of("zhoujielun");
        });
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class))).thenReturn(new int[][]{{1}});
        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config, tasks::add);

        service.enqueueUnresolvedProfilesAsync();
        Runnable firstTask = tasks.poll();
        CompletableFuture<Void> firstRun = CompletableFuture.runAsync(firstTask);
        try {
            assertThat(firstRefreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            service.enqueueUnresolvedProfilesAsync();
        } finally {
            allowFirstRefreshToFinish.countDown();
        }
        firstRun.get(5, TimeUnit.SECONDS);
        Runnable followUp = tasks.poll();
        assertThat(followUp).isNotNull();
        followUp.run();

        assertThat(refreshCount).hasValue(2);
        assertThat(tasks).isEmpty();
        verify(jdbc, org.mockito.Mockito.times(2)).batchUpdate(anyString(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
    }
}
