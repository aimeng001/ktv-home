package com.homektv.musicsource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ExternalTrackStorage {
    private static final Logger log = LoggerFactory.getLogger(ExternalTrackStorage.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ExternalTrackStorage(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<List<ExternalTrack>> cachedSearch(MusicProvider provider, String query, int limit) {
        String key = searchKey(provider, query, limit);
        List<String> rows = jdbc.query("SELECT result_json::text FROM music_source_search_cache WHERE cache_key=? AND expires_at>now()",
                (rs, i) -> rs.getString(1), key);
        if (rows.isEmpty()) return Optional.empty();
        try { return Optional.of(mapper.readValue(rows.getFirst(), new TypeReference<>() {})); }
        catch (Exception ex) { return Optional.empty(); }
    }

    public void saveSearch(MusicProvider provider, String query, int limit, List<ExternalTrack> tracks, int cacheHours) {
        OffsetDateTime fetchedAt = OffsetDateTime.now();
        for (ExternalTrack track : tracks) upsertTrack(track, fetchedAt.plusDays(7));
        try {
            String json = mapper.writeValueAsString(tracks);
            jdbc.update("""
                    INSERT INTO music_source_search_cache(cache_key,provider,normalized_query,result_json,fetched_at,expires_at)
                    VALUES (?,?,?,CAST(? AS jsonb),?,?) ON CONFLICT(cache_key) DO UPDATE SET
                    result_json=EXCLUDED.result_json,fetched_at=EXCLUDED.fetched_at,expires_at=EXCLUDED.expires_at
                    """, searchKey(provider, query, limit), provider.name(), normalizeQuery(query), json,
                    fetchedAt, fetchedAt.plusHours(cacheHours));
        } catch (Exception ex) {
            log.warn("search cache write skipped for {}: {}", provider, ex.getMessage());
        }
    }

    public void upsertTrack(ExternalTrack track, OffsetDateTime expiresAt) {
        try {
            jdbc.update("""
                    INSERT INTO music_source_tracks(provider,external_id,title,artists,album,duration_ms,release_date,aliases,cover_url,availability,fetched_at,expires_at)
                    VALUES (?,?,?,CAST(? AS jsonb),?,?,?,CAST(? AS jsonb),?,?,?,?)
                    ON CONFLICT(provider,external_id) DO UPDATE SET title=EXCLUDED.title,artists=EXCLUDED.artists,
                    album=EXCLUDED.album,duration_ms=EXCLUDED.duration_ms,release_date=EXCLUDED.release_date,
                    aliases=EXCLUDED.aliases,cover_url=EXCLUDED.cover_url,availability=EXCLUDED.availability,
                    fetched_at=EXCLUDED.fetched_at,expires_at=EXCLUDED.expires_at
                    """, track.provider().name(), track.externalId(), track.title(), mapper.writeValueAsString(track.artists()),
                    track.album(), track.durationMs(), track.releaseDate(), mapper.writeValueAsString(track.aliases()),
                    track.coverUrl(), track.availability(), track.fetchedAt(), expiresAt);
        } catch (Exception ex) {
            log.warn("external track cache write skipped for {} {}: {}", track.provider(), track.externalId(), ex.getMessage());
        }
    }

    public Optional<ExternalTrack> track(MusicProvider provider, String externalId, boolean requireFresh) {
        String sql = """
                SELECT provider,external_id,title,artists::text,album,duration_ms,release_date,aliases::text,
                       cover_url,availability,fetched_at FROM music_source_tracks
                WHERE provider=? AND external_id=?
                """ + (requireFresh ? " AND expires_at>now()" : "");
        List<ExternalTrack> rows = jdbc.query(sql, (rs, i) -> {
            try {
                return new ExternalTrack(MusicProvider.valueOf(rs.getString("provider")), rs.getString("external_id"),
                        rs.getString("title"), mapper.readValue(rs.getString("artists"), new TypeReference<>() {}),
                        rs.getString("album"), (Integer) rs.getObject("duration_ms"), rs.getString("release_date"),
                        mapper.readValue(rs.getString("aliases"), new TypeReference<>() {}), rs.getString("cover_url"),
                        rs.getString("availability"), rs.getObject("fetched_at", OffsetDateTime.class));
            } catch (Exception ex) { throw new IllegalStateException(ex); }
        }, provider.name(), externalId);
        return rows.stream().findFirst();
    }

    @Transactional
    public void saveMatch(long songId, ExternalTrack track, double score) {
        upsertTrack(track, OffsetDateTime.now().plusDays(7));
        jdbc.update("""
                INSERT INTO song_external_matches(song_id,provider,external_id,match_score,status,field_sources,created_at,updated_at)
                VALUES (?,?,?,?, 'SUGGESTED',CAST(? AS jsonb),now(),now())
                ON CONFLICT(song_id,provider,external_id) DO UPDATE SET match_score=EXCLUDED.match_score,
                field_sources=EXCLUDED.field_sources,updated_at=now()
                """, songId, track.provider().name(), track.externalId(), score,
                "{\"title\":\"" + track.provider().name() + "\",\"artist\":\"" + track.provider().name() + "\"}");
    }

    @Transactional
    public void markApplied(long songId, MusicProvider provider, String externalId) {
        jdbc.update("""
                UPDATE song_external_matches SET status='APPLIED',confirmed_at=now(),applied_at=now(),updated_at=now()
                WHERE song_id=? AND provider=? AND external_id=?
                """, songId, provider.name(), externalId);
    }

    private static String normalizeQuery(String query) {
        return query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String searchKey(MusicProvider provider, String query, int limit) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((provider.name() + "|" + normalizeQuery(query) + "|" + limit).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
