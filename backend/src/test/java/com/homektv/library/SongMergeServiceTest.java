package com.homektv.library;

import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SongMergeServiceTest {
    private JdbcTemplate jdbc;
    private SongMergeService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new SongMergeService(jdbc);
    }

    @Test
    void rejectsMissingOrIdenticalSongIdsBeforeQueryingTheDatabase() {
        assertInvalidMerge(null, 2L);
        assertInvalidMerge(1L, null);
        assertInvalidMerge(1L, 1L);

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsEitherSongWhenTheLockedRowsDoNotContainBothIds() {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(2L), List.of(1L));

        assertThatThrownBy(() -> service.merge(1L, 2L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SONG_NOT_FOUND");
        assertThatThrownBy(() -> service.merge(1L, 2L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SONG_NOT_FOUND");

        verify(jdbc, times(2)).queryForList(anyString(), eq(Long.class), any(Object[].class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void migratesEveryReferenceAndReportsPrimaryFileWhenPresent(boolean hasPrimaryFile) throws Exception {
        Long primaryFileId = hasPrimaryFile ? 77L : null;
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(1L, 2L));
        AtomicInteger artistCreditWrites = new AtomicInteger();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("INSERT INTO playlist_songs")) return 4;
            if (sql.contains("INSERT INTO favorites")) return 5;
            if (sql.startsWith("UPDATE queue")) return 6;
            if (sql.startsWith("UPDATE play_history")) return 7;
            if (sql.startsWith("UPDATE song_files")) return 8;
            if (sql.startsWith("UPDATE media_import_records")) return 9;
            if (sql.contains("UPDATE ai_analysis_tasks SET song_id")) return 10;
            if (sql.contains("INSERT INTO song_artists")) return 11 + artistCreditWrites.getAndIncrement();
            return 0;
        }).when(jdbc).update(anyString(), any(Object[].class));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultSetExtractor<Long> extractor = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(hasPrimaryFile);
            if (hasPrimaryFile) when(resultSet.getLong(1)).thenReturn(primaryFileId);
            return extractor.extractData(resultSet);
        }).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));

        Map<String, Object> result = service.merge(1L, 2L);

        assertThat(result).containsEntry("status", "merged")
                .containsEntry("keepSongId", 1L)
                .containsEntry("sourceSongId", 2L)
                .containsEntry("primaryFileId", hasPrimaryFile ? 77L : 0L);
        @SuppressWarnings("unchecked")
        Map<String, Integer> migrated = (Map<String, Integer>) result.get("migrated");
        assertThat(migrated).containsExactlyInAnyOrderEntriesOf(Map.of(
                "playlistSongs", 4,
                "favorites", 5,
                "queueItems", 6,
                "history", 7,
                "songFiles", 8,
                "importRecords", 9,
                "aiTasks", 10,
                "artistCredits", 36));

        verify(jdbc).update("DELETE FROM songs WHERE id = ?", 2L);
        verify(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    private void assertInvalidMerge(Long keepSongId, Long sourceSongId) {
        assertThatThrownBy(() -> service.merge(keepSongId, sourceSongId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_SONG_MERGE");
    }
}
