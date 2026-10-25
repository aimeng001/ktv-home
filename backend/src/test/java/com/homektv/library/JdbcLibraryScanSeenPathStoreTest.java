package com.homektv.library;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcLibraryScanSeenPathStoreTest {
    private static final UUID SCAN_ID = UUID.fromString("f6c1cf14-5bb1-49c2-96d3-a6b87321a773");

    private JdbcTemplate jdbc;
    private JdbcLibraryScanSeenPathStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new JdbcLibraryScanSeenPathStore(jdbc);
    }

    @Test
    void recordBatchIgnoresEmptyInputsAndPersistsDistinctNonblankPaths() throws Exception {
        List<String> writtenPaths = new ArrayList<>();
        List<String> sql = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>();
        PreparedStatement statement = mock(PreparedStatement.class);
        doAnswer(invocation -> {
            sql.add(invocation.getArgument(0));
            Collection<String> paths = invocation.getArgument(1);
            batchSizes.add(invocation.getArgument(2));
            ParameterizedPreparedStatementSetter<String> setter = invocation.getArgument(3);
            for (String path : paths) {
                writtenPaths.add(path);
                setter.setValues(statement, path);
            }
            return new int[paths.size()][1];
        }).when(jdbc).batchUpdate(anyString(), anyCollection(), anyInt(), any());

        store.recordBatch(SCAN_ID, "EXTERNAL", null);
        store.recordBatch(SCAN_ID, "EXTERNAL", List.of());
        store.recordBatch(SCAN_ID, "EXTERNAL", java.util.Arrays.asList(null, "", "  "));
        store.recordBatch(SCAN_ID, "EXTERNAL", java.util.Arrays.asList(
                "/music/a.mkv", null, " ", "/music/a.mkv", "/music/b.mkv"));

        assertThat(writtenPaths).containsExactly("/music/a.mkv", "/music/b.mkv");
        assertThat(batchSizes).containsExactly(500);
        assertThat(sql).singleElement().satisfies(statementSql -> {
            assertThat(statementSql).contains("pending_at_start) VALUES (?, ?, ?, FALSE)");
            assertThat(statementSql).contains("ON CONFLICT (scan_id, file_role, file_path) DO NOTHING");
        });
        verify(statement, times(2)).setObject(1, SCAN_ID);
        verify(statement, times(2)).setString(2, "EXTERNAL");
        verify(statement).setString(3, "/music/a.mkv");
        verify(statement).setString(3, "/music/b.mkv");
        verify(jdbc, times(1)).batchUpdate(anyString(), anyCollection(), anyInt(), any());
    }

    @Test
    void recordPendingBatchUsesUpsertAndMarksOnlyNonblankDistinctPathsPending() throws Exception {
        List<String> writtenPaths = new ArrayList<>();
        List<String> sql = new ArrayList<>();
        PreparedStatement statement = mock(PreparedStatement.class);
        doAnswer(invocation -> {
            sql.add(invocation.getArgument(0));
            Collection<String> paths = invocation.getArgument(1);
            ParameterizedPreparedStatementSetter<String> setter = invocation.getArgument(3);
            for (String path : paths) {
                writtenPaths.add(path);
                setter.setValues(statement, path);
            }
            return new int[paths.size()][1];
        }).when(jdbc).batchUpdate(anyString(), anyCollection(), anyInt(), any());

        store.recordPendingBatch(SCAN_ID, "MANAGED", null);
        store.recordPendingBatch(SCAN_ID, "MANAGED", List.of());
        store.recordPendingBatch(SCAN_ID, "MANAGED", java.util.Arrays.asList(
                "/music/pending.mkv", null, "", "/music/pending.mkv", "/music/other.mkv"));
        store.recordPendingBatch(SCAN_ID, "MANAGED", List.of(" ", "\t"));

        assertThat(writtenPaths).containsExactly("/music/pending.mkv", "/music/other.mkv");
        assertThat(sql).singleElement().satisfies(statementSql -> {
            assertThat(statementSql).contains("pending_at_start) VALUES (?, ?, ?, TRUE)");
            assertThat(statementSql).contains("DO UPDATE SET pending_at_start = TRUE");
        });
        verify(statement, times(2)).setString(2, "MANAGED");
        verify(statement).setString(3, "/music/pending.mkv");
        verify(statement).setString(3, "/music/other.mkv");
        verify(jdbc, times(1)).batchUpdate(anyString(), anyCollection(), anyInt(), any());
    }

    @Test
    void findPendingAtStartShortCircuitsNullEmptyAndBlankCollections() {
        assertThat(store.findPendingAtStart(SCAN_ID, "EXTERNAL", null)).isEmpty();
        assertThat(store.findPendingAtStart(SCAN_ID, "EXTERNAL", List.of())).isEmpty();
        assertThat(store.findPendingAtStart(SCAN_ID, "EXTERNAL", List.of("", " ", "\t"))).isEmpty();

        verify(jdbc, never()).query(anyString(), any(Object[].class), any(RowMapper.class));
    }

    @Test
    void findPendingAtStartQueriesDistinctPathsInPagesAndMapsRows() throws Exception {
        List<String> paths = new ArrayList<>();
        for (int index = 0; index <= 500; index++) {
            paths.add("/music/" + index + ".mkv");
        }
        paths.add(17, "/music/0.mkv");
        paths.add(null);
        paths.add(" ");

        AtomicInteger page = new AtomicInteger();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<String> mapper = invocation.getArgument(2);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString(1)).thenReturn(page.getAndIncrement() == 0
                    ? "/music/0.mkv" : "/music/500.mkv");
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbc).query(anyString(), any(Object[].class), any(RowMapper.class));

        Set<String> result = store.findPendingAtStart(SCAN_ID, "EXTERNAL", paths);

        assertThat(result).containsExactlyInAnyOrder("/music/0.mkv", "/music/500.mkv");
        verify(jdbc, times(2)).query(anyString(), any(Object[].class), any(RowMapper.class));
    }

    @Test
    void markMissingBindsBothRootSeparatorsAndReturnsCounts() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getInt("files_marked")).thenReturn(3);
        when(resultSet.getInt("songs_marked")).thenReturn(2);
        doAnswer(invocation -> {
            PreparedStatementSetter setter = invocation.getArgument(1);
            RowMapper<LibraryScanSeenPathStore.MissingFiles> mapper = invocation.getArgument(2);
            setter.setValues(statement);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbc).query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class));

        LibraryScanSeenPathStore.MissingFiles missing = store.markMissing(SCAN_ID, "EXTERNAL", "/media/music");

        assertThat(missing).isEqualTo(new LibraryScanSeenPathStore.MissingFiles(3, 2));
        verify(statement).setString(1, "EXTERNAL");
        verify(statement).setString(2, "/media/music");
        verify(statement).setString(3, "/media/music\\");
        verify(statement).setString(4, "/media/music\\");
        verify(statement).setString(5, "/media/music/");
        verify(statement).setString(6, "/media/music/");
        verify(statement).setObject(7, SCAN_ID);
    }

    @Test
    void markMissingNormalizesRootsAlreadyEndingInSlashOrBackslash() throws Exception {
        assertMarkMissingRoot("/media/music/", "/media/music/", "/media/music/");
        assertMarkMissingRoot("C:\\media\\music\\", "C:\\media\\music\\", "C:\\media\\music\\");
        assertMarkMissingRoot(null, "\\", "/");

        verify(jdbc, times(3)).query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class));
    }

    @Test
    void markMissingReturnsZeroCountsWhenTheUpdateFindsNoRows() {
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());

        assertThat(store.markMissing(SCAN_ID, "EXTERNAL", "")).isEqualTo(
                new LibraryScanSeenPathStore.MissingFiles(0, 0));
    }

    @Test
    void deleteRemovesOnlyTheSelectedScanRows() {
        store.delete(SCAN_ID);

        verify(jdbc).update("DELETE FROM library_scan_seen_paths WHERE scan_id = ?", SCAN_ID);
    }

    private void assertMarkMissingRoot(String root, String expectedBackslashPrefix,
                                       String expectedSlashPrefix) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getInt("files_marked")).thenReturn(0);
        when(resultSet.getInt("songs_marked")).thenReturn(0);
        doAnswer(invocation -> {
            PreparedStatementSetter setter = invocation.getArgument(1);
            RowMapper<LibraryScanSeenPathStore.MissingFiles> mapper = invocation.getArgument(2);
            setter.setValues(statement);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbc).query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class));

        store.markMissing(SCAN_ID, "EXTERNAL", root);

        verify(statement).setString(3, expectedBackslashPrefix);
        verify(statement).setString(4, expectedBackslashPrefix);
        verify(statement).setString(5, expectedSlashPrefix);
        verify(statement).setString(6, expectedSlashPrefix);
    }
}
