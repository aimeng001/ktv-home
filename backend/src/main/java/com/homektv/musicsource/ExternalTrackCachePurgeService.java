package com.homektv.musicsource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalTrackCachePurgeService {
    private final JdbcTemplate jdbc;

    public ExternalTrackCachePurgeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${home-ktv.metadata-cache.purge-delay-ms:3600000}")
    @Transactional
    public PurgeResult purgeExpired() {
        int searchCacheRows = jdbc.update("""
                DELETE FROM music_source_search_cache
                WHERE expires_at <= now()
                """);
        int trackRows = jdbc.update("""
                DELETE FROM music_source_tracks t
                WHERE t.expires_at <= now()
                  AND NOT EXISTS (
                      SELECT 1
                      FROM song_external_matches m
                      WHERE m.provider = t.provider
                        AND m.external_id = t.external_id
                  )
                """);
        return new PurgeResult(searchCacheRows, trackRows);
    }

    public record PurgeResult(int searchCacheRows, int trackRows) { }
}
