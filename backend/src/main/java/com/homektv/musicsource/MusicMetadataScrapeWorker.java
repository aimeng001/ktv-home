package com.homektv.musicsource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
public class MusicMetadataScrapeWorker {
    private static final Set<String> APPLY_FIELDS = Set.of("title", "artist", "album", "releaseDate", "aliases", "cover");
    private final JdbcTemplate jdbc;
    private final MusicSourceSearchService searchService;
    private final MusicMetadataApplyService applyService;
    private final MusicSourceConfigService configService;
    private final ObjectMapper mapper;
    private final String workerId = UUID.randomUUID().toString();

    public MusicMetadataScrapeWorker(JdbcTemplate jdbc, MusicSourceSearchService searchService,
                                     MusicMetadataApplyService applyService, MusicSourceConfigService configService,
                                     ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.searchService = searchService;
        this.applyService = applyService;
        this.configService = configService;
        this.mapper = mapper;
    }

    @Async("metadataScrapeExecutor")
    public void process(String batchId) {
        if (!isRunning(batchId)) return;
        String claimToken = UUID.randomUUID().toString();
        jdbc.update("UPDATE music_metadata_scrape_batches SET started_at=COALESCE(started_at,now()),updated_at=now() WHERE id=?", batchId);
        try (var executor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(configService.getConfig().concurrencyLimit(),
                        MetadataScrapeBatchPolicy.MAX_CLAIM_SIZE)))) {
            while (isRunning(batchId)) {
                List<Long> itemIds = claimPendingItemIds(batchId, claimToken);
                if (itemIds.isEmpty()) break;
                List<CompletableFuture<Void>> futures = itemIds.stream()
                        .map(id -> CompletableFuture.runAsync(() -> processItem(batchId, id, claimToken), executor)).toList();
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            }
        }
        finishIfDone(batchId);
    }

    private List<Long> claimPendingItemIds(String batchId, String claimToken) {
        int limit = MetadataScrapeBatchPolicy.safeBatchSize(
                MetadataScrapeBatchPolicy.MAX_CLAIM_SIZE);
        return jdbc.query("""
                WITH candidates AS (
                    SELECT id
                    FROM music_metadata_scrape_items
                    WHERE batch_id=? AND status='PENDING'
                    ORDER BY id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE music_metadata_scrape_items item
                SET status='PROCESSING',
                    lease_owner=?,
                    claim_token=?,
                    lease_until=now() + interval '5 minutes',
                    started_at=COALESCE(started_at,now()),
                    error_message=NULL,
                    updated_at=now()
                FROM candidates
                WHERE item.id=candidates.id
                RETURNING item.id
                """, (rs, index) -> rs.getLong(1), batchId, limit, workerId, claimToken);
    }

    private void processItem(String batchId, long itemId, String claimToken) {
        if (!isRunning(batchId)) return;
        ItemTarget target = jdbc.query("""
                SELECT song_id FROM music_metadata_scrape_items
                WHERE id=? AND batch_id=? AND status='PROCESSING' AND lease_owner=? AND claim_token=?
                """, rs -> rs.next() ? new ItemTarget((Long) rs.getObject(1)) : null,
                itemId, batchId, workerId, claimToken);
        if (target == null || target.songId() == null) {
            fail(batchId, itemId, claimToken, "歌曲已删除");
            return;
        }
        try {
            List<MusicSourceSearchService.SongMatch> matches = searchService.matches(target.songId(), false);
            MusicSourceSearchService.SongMatch best = matches.isEmpty() ? null : matches.getFirst();
            if (best == null) {
                review(batchId, itemId, claimToken, null, "未找到可用的元数据候选");
                return;
            }
            ExternalTrack track = best.track();
            String json = mapper.writeValueAsString(best);
            jdbc.update("""
                    UPDATE music_metadata_scrape_items SET provider=?,external_id=?,match_score=?,result_json=CAST(? AS jsonb),updated_at=now()
                    WHERE id=? AND batch_id=? AND status='PROCESSING' AND lease_owner=? AND claim_token=?
                    """, track.provider().name(), track.externalId(), best.score(), json, itemId, batchId, workerId, claimToken);
            if (!canApply(batchId, itemId, claimToken)) {
                release(batchId, itemId, claimToken);
                return;
            }
            double threshold = jdbc.queryForObject(
                    "SELECT auto_apply_threshold FROM music_metadata_scrape_batches WHERE id=?", Double.class, batchId);
            if (best.score() < threshold) {
                review(batchId, itemId, claimToken, best, "匹配度低于自动写入阈值，等待人工审核");
                return;
            }
            // Pause is cooperative for an already running provider request,
            // but no new metadata write may begin after the batch is paused.
            // Recheck both the batch state and the claim immediately before
            // entering the transactional apply service.
            if (!canApply(batchId, itemId, claimToken)) {
                release(batchId, itemId, claimToken);
                return;
            }
            try {
                applyService.apply(target.songId(), track.provider(), track.externalId(),
                        new MusicMetadataApplyService.ApplyRequest(APPLY_FIELDS));
                terminal(batchId, itemId, claimToken, "AUTO_APPLIED", null);
            } catch (RuntimeException ex) {
                review(batchId, itemId, claimToken, best, "自动写入未执行：" + safe(ex));
            }
        } catch (Exception ex) {
            fail(batchId, itemId, claimToken, safe(ex));
        }
    }

    private boolean isRunning(String batchId) {
        List<String> values = jdbc.query("SELECT status FROM music_metadata_scrape_batches WHERE id=?",
                (rs, index) -> rs.getString(1), batchId);
        return !values.isEmpty() && "RUNNING".equals(values.getFirst());
    }

    /** Package-visible for a focused race-regression test. */
    boolean canApply(String batchId, long itemId, String claimToken) {
        return isRunning(batchId) && hasClaim(batchId, itemId, claimToken);
    }

    private void review(String batchId, long itemId, String claimToken,
                        MusicSourceSearchService.SongMatch match, String message) {
        terminal(batchId, itemId, claimToken, "REVIEW", message);
    }

    private void fail(String batchId, long itemId, String claimToken, String message) {
        terminal(batchId, itemId, claimToken, "FAILED", message);
    }

    private void terminal(String batchId, long itemId, String claimToken, String status, String message) {
        jdbc.update("""
                UPDATE music_metadata_scrape_items
                SET status=?,error_message=?,finished_at=now(),
                    lease_owner=NULL,lease_until=NULL,claim_token=NULL,updated_at=now()
                WHERE id=? AND batch_id=? AND status='PROCESSING'
                  AND lease_owner=? AND claim_token=?
                """, status, ProviderJson.clean(message, 1000), itemId, batchId, workerId, claimToken);
    }

    private boolean hasClaim(String batchId, long itemId, String claimToken) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM music_metadata_scrape_items
                WHERE id=? AND batch_id=? AND status='PROCESSING'
                  AND lease_owner=? AND claim_token=?
                """, Integer.class, itemId, batchId, workerId, claimToken);
        return count != null && count == 1;
    }

    private void release(String batchId, long itemId, String claimToken) {
        jdbc.update("""
                UPDATE music_metadata_scrape_items
                SET status='PENDING',lease_owner=NULL,lease_until=NULL,
                    claim_token=NULL,updated_at=now()
                WHERE id=? AND batch_id=? AND status='PROCESSING'
                  AND lease_owner=? AND claim_token=?
                """, itemId, batchId, workerId, claimToken);
    }

    private void finishIfDone(String batchId) {
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM music_metadata_scrape_items WHERE batch_id=? AND status IN ('PENDING','PROCESSING')
                """, Integer.class, batchId);
        if (active != null && active == 0) {
            jdbc.update("""
                    UPDATE music_metadata_scrape_batches SET status='COMPLETED',finished_at=now(),updated_at=now()
                    WHERE id=? AND status='RUNNING'
                    """, batchId);
        }
    }

    private static String safe(Throwable ex) {
        String value = ex.getMessage();
        if ((value == null || value.isBlank()) && ex.getCause() != null) value = ex.getCause().getMessage();
        return ProviderJson.clean(value == null || value.isBlank() ? ex.getClass().getSimpleName() : value, 1000);
    }

    private record ItemTarget(Long songId) {}
}
