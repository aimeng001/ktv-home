package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetCleanupResourceBudgetTest {

    @Test
    void reference_query_failure_fails_closed_without_sweeping_files() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AssetWriter writer = mock(AssetWriter.class);
        Path root = Files.createTempDirectory("asset-cleanup-failure-");
        when(writer.dataRootForCleanup()).thenReturn(root);
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(jdbc).query(anyString(), any(ResultSetExtractor.class));

        AssetCleanupService service = new AssetCleanupService(jdbc, writer, new ObjectMapper());

        assertThat(service.sweepOrphans()).isZero();
        verify(writer, never()).deleteReadableCache(anyString());
    }

    @Tag("extended")
    @Test
    void reference_budget_exhaustion_fails_closed_without_deleting_anything() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AssetWriter writer = mock(AssetWriter.class);
        Path root = Files.createTempDirectory("asset-cleanup-budget-");
        when(writer.dataRootForCleanup()).thenReturn(root);
        ResultSet rows = mock(ResultSet.class);
        AtomicInteger count = new AtomicInteger();
        when(rows.next()).thenAnswer(invocation -> count.getAndIncrement() <= 1_000_000);
        when(rows.getString(1)).thenAnswer(invocation -> "covers/" + count.get());
        doAnswer(invocation -> {
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            extractor.extractData(rows);
            return null;
        }).when(jdbc).query(anyString(), any(ResultSetExtractor.class));

        AssetCleanupService service = new AssetCleanupService(jdbc, writer, new ObjectMapper());

        assertThat(service.sweepOrphans()).isZero();
        verify(writer, never()).deleteReadableCache(anyString());
        verify(jdbc, never()).query(anyString(), any(RowMapper.class));
    }
}
