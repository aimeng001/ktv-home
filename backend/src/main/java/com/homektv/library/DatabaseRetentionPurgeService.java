package com.homektv.library;

import com.homektv.resource.PlaybackPressureMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Bounded retention maintenance for high-growth batch/audit item tables.
 * Preserves user-owned catalog tables (songs/favorites/playlists/play_history).
 */
@Service
public class DatabaseRetentionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRetentionPurgeService.class);
    private static final int BATCH_LIMIT = 5_000;

    private final JdbcTemplate jdbc;
    private final Duration retentionAge;
    private volatile PlaybackPressureMonitor playbackPressureMonitor;

    @Autowired
    public DatabaseRetentionPurgeService(
            JdbcTemplate jdbc,
            @Value("${home-ktv.retention.days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.retentionAge = Duration.ofDays(Math.max(7, retentionDays));
    }

    @Autowired(required = false)
    void setPlaybackPressureMonitor(PlaybackPressureMonitor playbackPressureMonitor) {
        this.playbackPressureMonitor = playbackPressureMonitor;
    }

    @Scheduled(
            fixedDelayString = "${home-ktv.retention.purge-delay-ms:86400000}",
            initialDelayString = "${home-ktv.retention.initial-delay-ms:300000}")
    public void scheduledPurge() {
        if (playbackPressureMonitor != null && playbackPressureMonitor.isPlaybackBusy()) {
            return;
        }
        purgeExpiredTerminalItems(Instant.now());
    }

    public int purgeExpiredTerminalItems(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(retentionAge));
        int totalDeleted = 0;

        totalDeleted += purgeBatch("""
                DELETE FROM music_metadata_scrape_items
                WHERE id IN (
                    SELECT item.id
                    FROM music_metadata_scrape_items item
                    JOIN music_metadata_scrape_batches batch ON batch.id = item.batch_id
                    WHERE batch.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                      AND item.updated_at < ?
                    ORDER BY item.id
                    LIMIT ?
                )
                """, cutoff);

        totalDeleted += purgeBatch("""
                DELETE FROM ai_analysis_tasks
                WHERE id IN (
                    SELECT id
                    FROM ai_analysis_tasks
                    WHERE status IN ('applied', 'failed', 'rejected', 'cancelled')
                      AND updated_at < ?
                    ORDER BY id
                    LIMIT ?
                )
                """, cutoff);

        totalDeleted += purgeBatch("""
                DELETE FROM iktv_song_match_batch_items
                WHERE id IN (
                    SELECT item.id
                    FROM iktv_song_match_batch_items item
                    JOIN iktv_song_match_batches batch ON batch.id = item.batch_id
                    WHERE batch.state IN ('PUBLISHED', 'ROLLED_BACK', 'CANCELLED', 'FAILED')
                      AND item.updated_at < ?
                    ORDER BY item.id
                    LIMIT ?
                )
                """, cutoff);

        if (totalDeleted > 0) {
            log.info("Retention purge removed {} expired terminal task items older than {}", totalDeleted, cutoff);
        }
        return totalDeleted;
    }

    private int purgeBatch(String sql, Timestamp cutoff) {
        int deleted = 0;
        for (int round = 0; round < 10; round++) {
            int batch;
            try {
                batch = jdbc.update(sql, cutoff, BATCH_LIMIT);
            } catch (InvalidDataAccessResourceUsageException missingSchema) {
                return deleted;
            }
            deleted += batch;
            if (batch < BATCH_LIMIT) break;
        }
        return deleted;
    }
}
