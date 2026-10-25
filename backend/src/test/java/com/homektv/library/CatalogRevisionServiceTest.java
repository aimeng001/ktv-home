package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogRevisionServiceTest {
    @Test
    void catalogMutationRefreshesDirectoryAndStats() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LibraryCatalogStatsService stats = mock(LibraryCatalogStatsService.class);
        ArtistDirectoryProjectionService projection = mock(ArtistDirectoryProjectionService.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        CatalogRevisionService service = new CatalogRevisionService(jdbc);
        service.setCatalogStats(stats);
        service.setDirectoryProjection(projection);
        service.bumpIfChanged(true);

        verify(stats).requestRefresh();
        verify(projection).requestRefresh();
    }

    @Test
    void unchangedCatalogReturnsExistingRevisionWithoutBumpingOrRefreshing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        stubCurrentRevision(jdbc, true, 7L);

        CatalogRevisionService service = new CatalogRevisionService(jdbc);

        assertThat(service.bumpIfChanged(false)).isEqualTo(7L);

        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void unchangedCatalogWithoutRevisionRowReturnsZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        stubCurrentRevision(jdbc, false, 0L);

        assertThat(new CatalogRevisionService(jdbc).bumpIfChanged(false)).isZero();
    }

    @Test
    void unchangedCatalogTreatsNullRevisionQueryResultAsZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(null).when(jdbc).query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));

        assertThat(new CatalogRevisionService(jdbc).bumpIfChanged(false)).isZero();
    }

    @Test
    void changedCatalogReturnsZeroWhenRevisionUpdateHasNoResultAndNoRefreshConsumers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);

        assertThat(new CatalogRevisionService(jdbc).bumpIfChanged(true)).isZero();
    }

    @Test
    void catalogStatsOnlyRefreshesImmediatelyOutsideTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LibraryCatalogStatsService stats = mock(LibraryCatalogStatsService.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(9L);
        CatalogRevisionService service = new CatalogRevisionService(jdbc);
        service.setCatalogStats(stats);

        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(service.bumpIfChanged(true)).isEqualTo(9L);

        verify(stats).requestRefresh();
    }

    @Test
    void projectionOnlyRefreshWaitsUntilTransactionCommits() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistDirectoryProjectionService projection = mock(ArtistDirectoryProjectionService.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(8L);
        CatalogRevisionService service = new CatalogRevisionService(jdbc);
        service.setDirectoryProjection(projection);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.bumpIfChanged(true)).isEqualTo(8L);
            verify(projection, never()).requestRefresh();

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(projection).requestRefresh();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void stubCurrentRevision(JdbcTemplate jdbc, boolean rowExists, long revision) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultSetExtractor<Long> extractor = invocation.getArgument(2);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(rowExists);
            when(resultSet.getLong(1)).thenReturn(revision);
            return extractor.extractData(resultSet);
        }).when(jdbc).query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));
    }
}
