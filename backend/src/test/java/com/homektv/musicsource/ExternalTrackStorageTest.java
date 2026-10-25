package com.homektv.musicsource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExternalTrackStorageTest {

    @Test
    void cachedSearchReturnsCachedRowsAndUsesNormalizedQueryKey() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = mapper();
        ExternalTrack expected = track("qq-1");
        String json = mapper.writeValueAsString(List.of(expected));
        List<String> keys = new ArrayList<>();
        doAnswer(invocation -> {
            Object bindValues = invocation.getArguments()[2];
            Object key = bindValues instanceof Object[] values ? values[0] : bindValues;
            keys.add(key.toString());
            return List.of(json);
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        ExternalTrackStorage storage = new ExternalTrackStorage(jdbc, mapper);

        Optional<List<ExternalTrack>> first = storage.cachedSearch(MusicProvider.QQ, "  New   Song\t", 12);
        Optional<List<ExternalTrack>> second = storage.cachedSearch(MusicProvider.QQ, "new song", 12);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        ExternalTrack actual = first.orElseThrow().getFirst();
        assertThat(actual).usingRecursiveComparison().ignoringFields("fetchedAt").isEqualTo(expected);
        assertThat(actual.fetchedAt().toInstant()).isEqualTo(expected.fetchedAt().toInstant());
        assertThat(keys).hasSize(2).allMatch(key -> key.matches("[0-9a-f]{64}"));
        assertThat(keys.getFirst()).isEqualTo(keys.getLast());
    }

    @Test
    void cachedSearchMissesWhenThereIsNoRowOrCachedJsonIsInvalid() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = mapper();
        List<List<String>> results = new ArrayList<>(List.of(List.of(), List.of("{invalid"), List.of()));
        doAnswer(invocation -> results.remove(0))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        ExternalTrackStorage storage = new ExternalTrackStorage(jdbc, mapper);

        assertThat(storage.cachedSearch(MusicProvider.QQ, "missing", 10)).isEmpty();
        assertThat(storage.cachedSearch(MusicProvider.QQ, "bad-json", 10)).isEmpty();
        assertThat(storage.cachedSearch(MusicProvider.QQ, null, 10)).isEmpty();
    }

    @Test
    void saveSearchUpsertsTracksAndStoresNormalizedSearchCache() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExternalTrackStorage storage = new ExternalTrackStorage(jdbc, mapper());
        List<String> sqls = new ArrayList<>();
        List<Object[]> bindValues = new ArrayList<>();
        doAnswer(invocation -> {
            sqls.add(invocation.getArgument(0));
            Object[] rawArguments = invocation.getRawArguments();
            Object[] bindArguments = rawArguments.length == 2 && rawArguments[1] instanceof Object[] values
                    ? values : Arrays.copyOfRange(rawArguments, 1, rawArguments.length);
            bindValues.add(bindArguments);
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));

        storage.saveSearch(MusicProvider.QQ, "  New   Song\t", 12, List.of(track("qq-1")), 24);

        assertThat(sqls).hasSize(2);
        assertThat(sqls.get(0)).contains("INSERT INTO music_source_tracks");
        assertThat(sqls.get(1)).contains("INSERT INTO music_source_search_cache");
        assertThat(bindValues.get(1)[1]).isEqualTo("QQ");
        assertThat(bindValues.get(1)[2]).isEqualTo("new song");
        assertThat(bindValues.get(1)[3].toString()).contains("qq-1");
    }

    @Test
    void persistenceFailuresDoNotFailSuccessfulSearches() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = mapper();
        doThrow(new IllegalStateException("database unavailable"))
                .when(jdbc).update(anyString(), any(Object[].class));
        ExternalTrackStorage storage = new ExternalTrackStorage(jdbc, mapper);

        storage.saveSearch(MusicProvider.QQ, "song", 10, List.of(track("qq-1")), 24);

        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void serializationFailureDoesNotFailSearchOrAttemptInvalidWrites() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("serialization unavailable") { })
                .when(mapper).writeValueAsString(any());
        ExternalTrackStorage storage = new ExternalTrackStorage(jdbc, mapper);

        storage.saveSearch(MusicProvider.QQ, "song", 10, List.of(track("qq-1")), 24);

        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    @Test
    void trackAddsFreshnessPredicateAndMapsStoredTrackFields() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = mapper();
        ResultSet row = trackRow("QQ");
        List<String> queries = new ArrayList<>();
        doAnswer(invocation -> {
            queries.add(invocation.getArgument(0));
            @SuppressWarnings("unchecked")
            RowMapper<ExternalTrack> rowMapper = invocation.getArgument(1);
            return List.of(rowMapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        ExternalTrackStorage storage = new ExternalTrackStorage(jdbc, mapper);

        ExternalTrack result = storage.track(MusicProvider.QQ, "qq-1", true).orElseThrow();

        assertThat(result).isEqualTo(new ExternalTrack(MusicProvider.QQ, "qq-1", "Song", List.of("Artist"),
                "Album", 240_000, "2026-01-01", List.of("Alias"), "https://cover.invalid/a.jpg",
                "AVAILABLE", OffsetDateTime.parse("2026-01-01T00:00:00Z")));
        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst()).contains("AND expires_at>now()");
    }

    @Test
    void trackReturnsEmptyWithoutRowsAndPropagatesInvalidStoredProvider() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = mapper();
        ExternalTrackStorage storage = new ExternalTrackStorage(jdbc, mapper);
        doAnswer(invocation -> List.of())
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        assertThat(storage.track(MusicProvider.QQ, "missing", false)).isEmpty();

        ResultSet row = mock(ResultSet.class);
        org.mockito.Mockito.when(row.getString("provider")).thenReturn("INVALID");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<ExternalTrack> rowMapper = invocation.getArgument(1);
            return List.of(rowMapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(() -> storage.track(MusicProvider.QQ, "bad", false))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveMatchAndMarkAppliedPersistExpectedStatusChanges() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExternalTrackStorage storage = new ExternalTrackStorage(jdbc, mapper());
        List<String> sqls = new ArrayList<>();
        doAnswer(invocation -> { sqls.add(invocation.getArgument(0)); return 1; })
                .when(jdbc).update(anyString(), any(Object[].class));

        storage.saveMatch(7L, track("qq-1"), 0.88);
        storage.markApplied(7L, MusicProvider.QQ, "qq-1");

        assertThat(sqls).hasSize(3);
        assertThat(sqls.get(0)).contains("INSERT INTO music_source_tracks");
        assertThat(sqls.get(1)).contains("INSERT INTO song_external_matches");
        assertThat(sqls.get(2)).contains("SET status='APPLIED'");
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status='APPLIED'"), any(Object[].class));
    }

    private static ResultSet trackRow(String provider) throws Exception {
        ResultSet row = mock(ResultSet.class);
        org.mockito.Mockito.when(row.getString("provider")).thenReturn(provider);
        org.mockito.Mockito.when(row.getString("external_id")).thenReturn("qq-1");
        org.mockito.Mockito.when(row.getString("title")).thenReturn("Song");
        org.mockito.Mockito.when(row.getString("artists")).thenReturn("[\"Artist\"]");
        org.mockito.Mockito.when(row.getString("album")).thenReturn("Album");
        org.mockito.Mockito.when(row.getObject("duration_ms")).thenReturn(240_000);
        org.mockito.Mockito.when(row.getString("release_date")).thenReturn("2026-01-01");
        org.mockito.Mockito.when(row.getString("aliases")).thenReturn("[\"Alias\"]");
        org.mockito.Mockito.when(row.getString("cover_url")).thenReturn("https://cover.invalid/a.jpg");
        org.mockito.Mockito.when(row.getString("availability")).thenReturn("AVAILABLE");
        org.mockito.Mockito.when(row.getObject("fetched_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return row;
    }

    private static ExternalTrack track(String id) {
        return new ExternalTrack(MusicProvider.QQ, id, "Song", List.of("Artist"), "Album", 240_000,
                "2026-01-01", List.of("Alias"), "https://cover.invalid/a.jpg", "AVAILABLE", null);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
