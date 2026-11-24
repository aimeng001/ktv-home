package com.homektv.musicsource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ExternalTrackCachePurgeService {
    private static final Logger log = LoggerFactory.getLogger(ExternalTrackCachePurgeService.class);
    private static final int PURGE_BATCH_SIZE = 5_000;
    private final JdbcTemplate jdbc;

    public ExternalTrackCachePurgeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${home-ktv.metadata-cache.purge-delay-ms:3600000}")
    public PurgeResult purgeExpired() {
        purgeExpiredSuggestedMatches();
        int searchCacheRows = deleteInBatches("""
                DELETE FROM music_source_search_cache
                WHERE cache_key IN (
                    SELECT cache_key FROM music_source_search_cache
                    WHERE expires_at <= now()
                    LIMIT ?
                )
                """);
        int trackRows = deleteInBatches("""
                DELETE FROM music_source_tracks
                WHERE (provider, external_id) IN (
                    SELECT t.provider, t.external_id
                    FROM music_source_tracks t
                    WHERE t.expires_at <= now()
                      AND NOT EXISTS (
                          SELECT 1
                          FROM song_external_matches m
                          WHERE m.provider = t.provider
                            AND m.external_id = t.external_id
                      )
                    LIMIT ?
                )
                """);
        return new PurgeResult(searchCacheRows, trackRows);
    }

    int purgeExpiredSuggestedMatches() {
        try {
            java.sql.Timestamp cutoff = java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(14)));
            return deleteInBatches("""
                    DELETE FROM song_external_matches
                    WHERE id IN (
                        SELECT id FROM song_external_matches
                        WHERE status = 'SUGGESTED'
                          AND updated_at <= ?
                        LIMIT ?
                    )
                    """, cutoff);
        } catch (org.springframework.jdbc.BadSqlGrammarException ex) {
            log.debug("skipped suggested match purge: {}", ex.getMessage());
            return 0;
        }
    }

    private int deleteInBatches(String sql, Object... args) {
        int total = 0;
        Object[] params = new Object[args.length + 1];
        System.arraycopy(args, 0, params, 0, args.length);
        params[args.length] = PURGE_BATCH_SIZE;
        while (true) {
            int deleted = jdbc.update(sql, params);
            total += deleted;
            if (deleted < PURGE_BATCH_SIZE) break;
        }
        return total;
    }

    public record PurgeResult(int searchCacheRows, int trackRows) { }
}
