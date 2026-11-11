package com.homektv.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Atomic batch gate, publication history, guarded rollback, and replay pause. */
@Service
public final class IktvSongMatchBatchService {
    private static final Logger log = LoggerFactory.getLogger(IktvSongMatchBatchService.class);
    private static final String ELIGIBLE_ITEM = """
            COALESCE(song.title, '') = item.input_title
            AND COALESCE(song.artist, '') = item.input_artist
            AND (COALESCE(song.catalog_number, '') = item.input_catalog_number
                 OR (item.input_catalog_number = '' AND item.proposed_catalog_number <> ''
                     AND COALESCE(song.catalog_number, '') = item.proposed_catalog_number))
            """;
    private static final String CATEGORIES_EQUAL = """
            NOT EXISTS (
                SELECT current.reference_song_id, current.category
                  FROM iktv_song_categories current
                 WHERE current.song_id = publication.song_id
                   AND current.snapshot_version = publication.snapshot_version
                EXCEPT
                SELECT (entry.value ->> 'reference_song_id')::bigint, entry.value ->> 'category'
                  FROM jsonb_array_elements(publication.applied_categories) AS entry(value)
            )
            AND NOT EXISTS (
                SELECT (entry.value ->> 'reference_song_id')::bigint, entry.value ->> 'category'
                  FROM jsonb_array_elements(publication.applied_categories) AS entry(value)
                EXCEPT
                SELECT current.reference_song_id, current.category
                  FROM iktv_song_categories current
                 WHERE current.song_id = publication.song_id
                   AND current.snapshot_version = publication.snapshot_version
            )
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CatalogRevisionService catalogRevisionService;

    public IktvSongMatchBatchService(JdbcTemplate jdbc,
                                     PlatformTransactionManager transactionManager,
                                     CatalogRevisionService catalogRevisionService) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.catalogRevisionService = catalogRevisionService;
    }

    /** Publishes only a fully staged batch against the still-active, validated reference snapshot. */
    public void tryPublishBatch(long batchId) {
        transaction.executeWithoutResult(status -> publishLocked(batchId));
    }

    private void publishLocked(long batchId) {
        Batch batch = jdbc.query("""
                SELECT id, snapshot_version, state, publication_state, total_count
                  FROM iktv_song_match_batches WHERE id = ? FOR UPDATE
                """, rs -> rs.next() ? new Batch(rs.getLong("id"), rs.getString("snapshot_version"),
                rs.getString("state"), rs.getString("publication_state"), rs.getLong("total_count")) : null,
                batchId);
        if (batch == null || !List.of("PENDING", "RUNNING").contains(batch.state())
                || !"STAGED".equals(batch.publicationState())) return;

        String pausedSnapshot = lockAndReadPausedSnapshot();
        if (batch.snapshotVersion().equals(pausedSnapshot)) return;

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM iktv_song_match_work WHERE batch_id = ?", Integer.class, batchId);
        if (remaining != null && remaining != 0) return;

        BatchGate gate = jdbc.query("""
                SELECT COUNT(*) AS item_count,
                       COUNT(*) FILTER (WHERE match_status = 'EXECUTION_FAILED') AS failed_count,
                       COUNT(*) FILTER (WHERE match_status NOT IN (
                           'MATCHED', 'NO_REFERENCE_CANDIDATE', 'AMBIGUOUS_VERSION', 'NUMBER_CONFLICT',
                           'NUMBER_IDENTITY_MISMATCH', 'INVALID_CATALOG_NUMBER', 'IDENTITY_UNAVAILABLE',
                           'PROTECTED_EXISTING_VALUE', 'OUT_OF_SCOPE'
                       )) AS invalid_count
                  FROM iktv_song_match_batch_items WHERE batch_id = ?
                """, rs -> {
            rs.next();
            return new BatchGate(rs.getLong("item_count"), rs.getLong("failed_count"), rs.getLong("invalid_count"));
        }, batchId);
        if (gate.itemCount() != batch.totalCount()) {
            failBatch(batchId, "BATCH_ITEM_COUNT_MISMATCH");
            return;
        }
        if (gate.failedCount() > 0 || gate.invalidCount() > 0) {
            failBatch(batchId, "BATCH_CONTAINS_FAILED_OR_UNKNOWN_ITEMS");
            return;
        }

        Boolean snapshotReady = jdbc.query("""
                SELECT expected_song_count = loaded_song_count
                       AND snapshot_sha256 ~ '^[a-f0-9]{64}$'
                  FROM iktv_reference_snapshots
                 WHERE snapshot_version = ? AND status = 'ACTIVE'
                 FOR SHARE
                """, rs -> rs.next() && rs.getBoolean(1), batch.snapshotVersion());
        if (!Boolean.TRUE.equals(snapshotReady)) {
            jdbc.update("""
                    UPDATE iktv_song_match_batches
                       SET state = 'REVOKED', publication_state = 'FAILED',
                           last_error_code = 'REFERENCE_SNAPSHOT_NOT_ACTIVE', completed_at = now()
                     WHERE id = ?
                    """, batchId);
            return;
        }

        lockBatchSongs(batchId);
        lockBatchItems(batchId);
        jdbc.update("""
                UPDATE iktv_song_match_batch_items item
                   SET match_status = 'PROTECTED_EXISTING_VALUE',
                       reason_code = 'HOME_IDENTITY_CHANGED_BEFORE_PUBLISH'
                  FROM songs song
                 WHERE item.batch_id = ? AND song.id = item.song_id
                   AND NOT (""" + ELIGIBLE_ITEM + ")",
                batchId);
        jdbc.update("""
                UPDATE iktv_song_match_batch_items item
                   SET match_status = 'PROTECTED_EXISTING_VALUE',
                       reason_code = 'CATALOG_NUMBER_METADATA_LOCKED_BEFORE_PUBLISH'
                  FROM songs song
                 WHERE item.batch_id = ? AND song.id = item.song_id
                   AND item.match_status = 'MATCHED'
                   AND item.proposed_catalog_number <> ''
                   AND COALESCE(song.catalog_number, '') = ''
                   AND (song.metadata_locks @> ARRAY['catalogNumber']::text[]
                        OR song.metadata_locks @> ARRAY['catalog_number']::text[])
                """, batchId);

        jdbc.update("UPDATE iktv_song_match_batches SET publication_state = 'PUBLISHING' WHERE id = ?", batchId);
        insertPublicationHistory(batchId);

        jdbc.queryForObject("SELECT set_config('homektv.reference_fusion', 'on', true)", String.class);
        publishCatalogNumbers(batchId);
        publishCategories(batchId);
        updatePublishedItemHistory(batchId);

        BatchCounts counts = counts(batchId);
        Long changed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_batch_publications
                 WHERE batch_id = ? AND (catalog_number_written OR categories_written)
                """, Long.class, batchId);
        if (changed != null && changed > 0) catalogRevisionService.bumpIfChanged(true);
        jdbc.update("""
                UPDATE iktv_song_match_batches
                   SET state = 'COMPLETED', publication_state = 'PUBLISHED', processed_count = ?,
                       matched_count = ?, unmatched_count = ?, conflict_count = ?, protected_count = ?,
                       out_of_scope_count = ?, failed_count = 0, classification_count = ?,
                       completed_at = now(), last_error_code = NULL
                 WHERE id = ?
                """, counts.processed(), counts.matched(), counts.unmatched(), counts.conflicts(),
                counts.protectedCount(), counts.outOfScope(), counts.classificationChanges(), batchId);
        log.info("IKTV match batch published: batchId={}, items={}, catalogChanges={}, categoryChanges={}",
                batchId, counts.processed(), counts.catalogChanges(), counts.classificationChanges());
    }

    private void insertPublicationHistory(long batchId) {
        jdbc.update("""
                INSERT INTO iktv_song_match_batch_publications (
                    batch_id, song_id, snapshot_version, previous_catalog_number, applied_catalog_number,
                    catalog_number_written, previous_categories, applied_categories, categories_written
                )
                SELECT item.batch_id, item.song_id, batch.snapshot_version,
                       COALESCE(song.catalog_number, ''),
                       CASE WHEN item.proposed_catalog_number <> ''
                                  AND COALESCE(song.catalog_number, '') = item.input_catalog_number
                                  AND NOT (song.metadata_locks @> ARRAY['catalogNumber']::text[])
                                  AND NOT (song.metadata_locks @> ARRAY['catalog_number']::text[])
                            THEN item.proposed_catalog_number ELSE COALESCE(song.catalog_number, '') END,
                       false,
                       COALESCE((SELECT jsonb_agg(jsonb_build_object(
                                  'reference_song_id', current.reference_song_id, 'category', current.category
                              ) ORDER BY current.reference_song_id, current.category)
                           FROM iktv_song_categories current
                          WHERE current.song_id = item.song_id
                            AND current.snapshot_version = batch.snapshot_version), '[]'::jsonb),
                       COALESCE((SELECT jsonb_agg(jsonb_build_object(
                                  'reference_song_id', proposed.reference_song_id, 'category', proposed.category
                              ) ORDER BY proposed.reference_song_id, proposed.category)
                           FROM iktv_song_match_batch_item_categories proposed
                          WHERE proposed.batch_id = item.batch_id AND proposed.song_id = item.song_id), '[]'::jsonb),
                       EXISTS (
                           (SELECT current.reference_song_id, current.category
                              FROM iktv_song_categories current
                             WHERE current.song_id = item.song_id
                               AND current.snapshot_version = batch.snapshot_version
                            EXCEPT
                            SELECT proposed.reference_song_id, proposed.category
                              FROM iktv_song_match_batch_item_categories proposed
                             WHERE proposed.batch_id = item.batch_id AND proposed.song_id = item.song_id)
                       ) OR EXISTS (
                           (SELECT proposed.reference_song_id, proposed.category
                              FROM iktv_song_match_batch_item_categories proposed
                             WHERE proposed.batch_id = item.batch_id AND proposed.song_id = item.song_id
                            EXCEPT
                            SELECT current.reference_song_id, current.category
                              FROM iktv_song_categories current
                             WHERE current.song_id = item.song_id
                               AND current.snapshot_version = batch.snapshot_version)
                       )
                  FROM iktv_song_match_batch_items item
                  JOIN iktv_song_match_batches batch ON batch.id = item.batch_id
                   JOIN songs song ON song.id = item.song_id
                  WHERE item.batch_id = ? AND
                """ + ELIGIBLE_ITEM,
                batchId);
    }

    private void publishCatalogNumbers(long batchId) {
        jdbc.update("""
                WITH changed AS (
                    UPDATE songs song
                       SET catalog_number = item.proposed_catalog_number
                      FROM iktv_song_match_batch_items item
                      JOIN iktv_song_match_batch_publications publication
                        ON publication.batch_id = item.batch_id AND publication.song_id = item.song_id
                     WHERE item.batch_id = ? AND item.match_status = 'MATCHED'
                       AND item.proposed_catalog_number <> ''
                       AND item.input_catalog_number = ''
                       AND COALESCE(song.catalog_number, '') = ''
                       AND NOT (song.metadata_locks @> ARRAY['catalogNumber']::text[])
                       AND NOT (song.metadata_locks @> ARRAY['catalog_number']::text[])
                       AND song.id = item.song_id
                    RETURNING item.batch_id, item.song_id, item.input_catalog_number, song.catalog_number
                )
                UPDATE iktv_song_match_batch_publications publication
                   SET catalog_number_written = true,
                       previous_catalog_number = changed.input_catalog_number,
                       applied_catalog_number = changed.catalog_number
                  FROM changed
                 WHERE publication.batch_id = changed.batch_id
                   AND publication.song_id = changed.song_id
                """, batchId);
    }

    private void publishCategories(long batchId) {
        jdbc.update("""
                DELETE FROM iktv_song_categories current
                 USING iktv_song_match_batch_publications publication,
                       iktv_song_match_batch_items item,
                       songs song
                 WHERE publication.batch_id = ? AND publication.categories_written
                   AND item.batch_id = publication.batch_id AND item.song_id = publication.song_id
                   AND song.id = item.song_id AND current.song_id = item.song_id
                   AND current.snapshot_version = publication.snapshot_version
                   AND
                """ + ELIGIBLE_ITEM,
                batchId);
        jdbc.update("""
                INSERT INTO iktv_song_categories (song_id, snapshot_version, reference_song_id, category)
                SELECT proposed.song_id, proposed.snapshot_version, proposed.reference_song_id, proposed.category
                  FROM iktv_song_match_batch_item_categories proposed
                  JOIN iktv_song_match_batch_publications publication
                    ON publication.batch_id = proposed.batch_id AND publication.song_id = proposed.song_id
                  JOIN iktv_song_match_batch_items item
                    ON item.batch_id = proposed.batch_id AND item.song_id = proposed.song_id
                  JOIN songs song ON song.id = item.song_id
                 WHERE proposed.batch_id = ? AND publication.categories_written
                   AND
                """ + ELIGIBLE_ITEM + " ON CONFLICT DO NOTHING",
                batchId);
    }

    private void updatePublishedItemHistory(long batchId) {
        jdbc.update("""
                UPDATE iktv_song_match_batch_items item
                   SET catalog_number_changed = publication.catalog_number_written,
                       previous_catalog_number = CASE WHEN publication.catalog_number_written
                                                      THEN publication.previous_catalog_number ELSE NULL END,
                       applied_catalog_number = CASE WHEN publication.catalog_number_written
                                                     THEN publication.applied_catalog_number ELSE NULL END,
                       classification_changed = publication.categories_written
                  FROM iktv_song_match_batch_publications publication
                 WHERE item.batch_id = ? AND publication.batch_id = item.batch_id
                   AND publication.song_id = item.song_id
                """, batchId);
        jdbc.update("""
                UPDATE iktv_song_match_results result
                   SET match_status = item.match_status,
                       reason_code = item.reason_code,
                       catalog_number_changed = item.catalog_number_changed,
                       previous_catalog_number = item.previous_catalog_number,
                       applied_catalog_number = item.applied_catalog_number,
                       matched_at = now()
                  FROM iktv_song_match_batch_items item
                 WHERE item.batch_id = ? AND result.song_id = item.song_id
                   AND result.batch_id = item.batch_id
                """, batchId);
    }

    private BatchCounts counts(long batchId) {
        return jdbc.query("""
                SELECT COUNT(*) AS processed,
                       COUNT(*) FILTER (WHERE match_status = 'MATCHED') AS matched,
                       COUNT(*) FILTER (WHERE match_status = 'NO_REFERENCE_CANDIDATE') AS unmatched,
                       COUNT(*) FILTER (WHERE match_status IN ('AMBIGUOUS_VERSION', 'NUMBER_CONFLICT',
                           'NUMBER_IDENTITY_MISMATCH', 'INVALID_CATALOG_NUMBER', 'IDENTITY_UNAVAILABLE')) AS conflicts,
                       COUNT(*) FILTER (WHERE match_status = 'PROTECTED_EXISTING_VALUE') AS protected_count,
                       COUNT(*) FILTER (WHERE match_status = 'OUT_OF_SCOPE') AS out_of_scope,
                       COUNT(*) FILTER (WHERE classification_changed) AS category_changes,
                       COUNT(*) FILTER (WHERE catalog_number_changed) AS catalog_changes
                  FROM iktv_song_match_batch_items WHERE batch_id = ?
                """, rs -> {
            rs.next();
            return new BatchCounts(rs.getLong("processed"), rs.getLong("matched"), rs.getLong("unmatched"),
                    rs.getLong("conflicts"), rs.getLong("protected_count"), rs.getLong("out_of_scope"),
                    rs.getLong("category_changes"), rs.getLong("catalog_changes"));
        }, batchId);
    }

    private void lockBatchSongs(long batchId) {
        jdbc.query("""
                SELECT song.id
                  FROM songs song
                  JOIN iktv_song_match_batch_items item ON item.song_id = song.id
                 WHERE item.batch_id = ?
                 ORDER BY song.id
                 FOR UPDATE OF song
                """, (RowCallbackHandler) rs -> { while (rs.next()) { /* consume rows while retaining locks */ } },
                batchId);
    }

    private void lockBatchItems(long batchId) {
        jdbc.query("""
                SELECT item.song_id
                  FROM iktv_song_match_batch_items item
                 WHERE item.batch_id = ?
                 ORDER BY item.song_id
                 FOR UPDATE OF item
                """, (RowCallbackHandler) rs -> { while (rs.next()) { /* retain the row locks */ } }, batchId);
    }

    private void failBatch(long batchId, String reason) {
        jdbc.update("""
                UPDATE iktv_song_match_batches
                   SET state = 'FAILED', publication_state = 'FAILED',
                       last_error_code = ?, completed_at = now()
                 WHERE id = ? AND publication_state = 'STAGED'
                """, reason, batchId);
        log.error("IKTV match batch failed its publication gate: batchId={}, reason={}", batchId, reason);
    }

    /** Read and lock the singleton control row; this serializes item processing/publication with rollback. */
    public String lockAndReadPausedSnapshot() {
        return jdbc.query("""
                SELECT paused_snapshot_version FROM iktv_reference_fusion_control
                 WHERE singleton_id = 1 FOR SHARE
                """, rs -> rs.next() ? rs.getString(1) : null);
    }

    public boolean isPaused(String snapshotVersion) {
        return snapshotVersion != null && snapshotVersion.equals(lockAndReadPausedSnapshot());
    }

    public RollbackResult rollback(long batchId) {
        return transaction.execute(status -> rollbackLocked(batchId));
    }

    private RollbackResult rollbackLocked(long batchId) {
        Batch batch = jdbc.query("""
                SELECT id, snapshot_version, state, publication_state, total_count
                  FROM iktv_song_match_batches WHERE id = ? FOR UPDATE
                """, rs -> rs.next() ? new Batch(rs.getLong("id"), rs.getString("snapshot_version"),
                rs.getString("state"), rs.getString("publication_state"), rs.getLong("total_count")) : null,
                batchId);
        if (batch == null) throw new IktvFusionException("IKTV_BATCH_NOT_FOUND", "融合批次不存在");
        if (!"PUBLISHED".equals(batch.publicationState())) {
            throw new IktvFusionException("IKTV_BATCH_NOT_REVERSIBLE", "该批次没有可撤销的完整发布历史");
        }
        Integer alreadyRolledBack = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_batch_publications
                 WHERE batch_id = ? AND rolled_back_at IS NOT NULL
                """, Integer.class, batchId);
        if (alreadyRolledBack != null && alreadyRolledBack > 0) {
            throw new IktvFusionException("IKTV_BATCH_ALREADY_ROLLED_BACK", "该批次已经执行过撤销");
        }
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_reference_snapshots
                 WHERE snapshot_version = ? AND status = 'ACTIVE'
                """, Integer.class, batch.snapshotVersion());
        if (active == null || active != 1) {
            throw new IktvFusionException("IKTV_BATCH_SNAPSHOT_NOT_ACTIVE", "只有当前活动快照的批次可以安全撤销");
        }

        jdbc.query("SELECT paused_snapshot_version FROM iktv_reference_fusion_control WHERE singleton_id = 1 FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null);
        lockPublicationSongs(batchId);
        Long missingReferences = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM iktv_song_match_batch_publications publication
                  CROSS JOIN LATERAL jsonb_array_elements(publication.previous_categories) entry(value)
                  LEFT JOIN iktv_reference_songs reference
                    ON reference.snapshot_version = publication.snapshot_version
                   AND reference.reference_song_id = (entry.value ->> 'reference_song_id')::bigint
                 WHERE publication.batch_id = ? AND reference.reference_song_id IS NULL
                """, Long.class, batchId);
        if (missingReferences != null && missingReferences > 0) {
            throw new IktvFusionException("IKTV_ROLLBACK_REFERENCE_MISSING", "撤销所需的原分类参考记录已不存在，未执行任何撤销");
        }

        long catalogConflicts = countCatalogConflicts(batchId);
        long categoryConflicts = countCategoryConflicts(batchId);
        long catalogRestored = countCatalogRestorable(batchId);
        long categoriesRestored = countCategoriesRestorable(batchId);

        jdbc.update("""
                UPDATE iktv_song_match_batch_publications publication
                   SET rollback_conflict_code = 'CURRENT_VALUE_CHANGED_AFTER_PUBLISH'
                 WHERE publication.batch_id = ? AND publication.catalog_number_written
                   AND (NOT EXISTS (SELECT 1 FROM songs song WHERE song.id = publication.song_id)
                        OR EXISTS (SELECT 1 FROM songs song WHERE song.id = publication.song_id
                                   AND COALESCE(song.catalog_number, '') <> publication.applied_catalog_number))
                """, batchId);
        jdbc.update("""
                UPDATE iktv_song_match_batch_publications publication
                   SET rollback_conflict_code = 'CURRENT_VALUE_CHANGED_AFTER_PUBLISH'
                 WHERE publication.batch_id = ? AND publication.categories_written
                   AND publication.rollback_conflict_code IS NULL
                   AND (NOT EXISTS (SELECT 1 FROM songs song WHERE song.id = publication.song_id)
                        OR NOT (""" + CATEGORIES_EQUAL + "))",
                batchId);
        jdbc.update("""
                UPDATE iktv_song_match_batch_publications publication
                   SET rollback_categories_eligible = true
                 WHERE publication.batch_id = ? AND publication.categories_written
                   AND EXISTS (SELECT 1 FROM songs song WHERE song.id = publication.song_id)
                   AND
                """ + CATEGORIES_EQUAL,
                batchId);
        jdbc.queryForObject("SELECT set_config('homektv.reference_fusion', 'on', true)", String.class);
        jdbc.update("""
                UPDATE songs song
                   SET catalog_number = publication.previous_catalog_number
                  FROM iktv_song_match_batch_publications publication
                 WHERE publication.batch_id = ? AND publication.catalog_number_written
                   AND song.id = publication.song_id
                   AND COALESCE(song.catalog_number, '') = publication.applied_catalog_number
                """, batchId);
        restoreCategories(batchId);
        jdbc.update("""
                UPDATE iktv_song_match_batch_publications
                   SET rolled_back_at = now(), rollback_categories_eligible = false
                 WHERE batch_id = ?
                """, batchId);

        long conflictRows = countRollbackConflictRows(batchId);
        String publicationState = conflictRows == 0 ? "ROLLED_BACK" : "ROLLBACK_PARTIAL";
        jdbc.update("""
                UPDATE iktv_reference_fusion_control
                   SET paused_snapshot_version = ?, paused_by_batch_id = ?,
                       pause_reason = 'BATCH_ROLLBACK', paused_at = now(), updated_at = now()
                 WHERE singleton_id = 1
                """, batch.snapshotVersion(), batchId);
        jdbc.update("""
                UPDATE iktv_song_match_batches
                   SET publication_state = ?, rollback_completed_at = now()
                 WHERE id = ?
                """, publicationState, batchId);

        if (catalogRestored > 0 || categoriesRestored > 0) catalogRevisionService.bumpIfChanged(true);
        log.warn("IKTV batch rollback completed: batchId={}, catalogRestored={}, categoriesRestored={}, conflicts={}, replayPaused=true",
                batchId, catalogRestored, categoriesRestored, conflictRows);
        return new RollbackResult(batchId, publicationState, catalogRestored, categoriesRestored,
                catalogConflicts + categoryConflicts, conflictRows);
    }

    private void lockPublicationSongs(long batchId) {
        jdbc.query("""
                SELECT song.id
                  FROM songs song
                  JOIN iktv_song_match_batch_publications publication ON publication.song_id = song.id
                 WHERE publication.batch_id = ?
                 ORDER BY song.id
                 FOR UPDATE OF song
                """, (RowCallbackHandler) rs -> { while (rs.next()) { /* consume rows while retaining locks */ } },
                batchId);
    }

    private long countCatalogConflicts(long batchId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_batch_publications publication
                  LEFT JOIN songs song ON song.id = publication.song_id
                 WHERE publication.batch_id = ? AND publication.catalog_number_written
                   AND (song.id IS NULL OR COALESCE(song.catalog_number, '') <> publication.applied_catalog_number)
                """, Long.class, batchId);
        return count == null ? 0 : count;
    }

    private long countCategoryConflicts(long batchId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_batch_publications publication
                  LEFT JOIN songs song ON song.id = publication.song_id
                 WHERE publication.batch_id = ? AND publication.categories_written
                   AND (song.id IS NULL OR NOT (""" + CATEGORIES_EQUAL + "))",
                Long.class, batchId);
        return count == null ? 0 : count;
    }

    private long countCatalogRestorable(long batchId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_batch_publications publication
                  JOIN songs song ON song.id = publication.song_id
                 WHERE publication.batch_id = ? AND publication.catalog_number_written
                   AND COALESCE(song.catalog_number, '') = publication.applied_catalog_number
                """, Long.class, batchId);
        return count == null ? 0 : count;
    }

    private long countCategoriesRestorable(long batchId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_batch_publications publication
                  JOIN songs song ON song.id = publication.song_id
                 WHERE publication.batch_id = ? AND publication.categories_written
                   AND
                """ + CATEGORIES_EQUAL,
                Long.class, batchId);
        return count == null ? 0 : count;
    }

    private long countRollbackConflictRows(long batchId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_batch_publications
                 WHERE batch_id = ? AND rollback_conflict_code IS NOT NULL
                """, Long.class, batchId);
        return count == null ? 0 : count;
    }

    private void restoreCategories(long batchId) {
        jdbc.update("""
                DELETE FROM iktv_song_categories current
                 USING iktv_song_match_batch_publications publication, songs song
                 WHERE publication.batch_id = ? AND publication.rollback_categories_eligible
                   AND song.id = publication.song_id
                   AND current.song_id = publication.song_id
                   AND current.snapshot_version = publication.snapshot_version
                   AND
                """ + CATEGORIES_EQUAL,
                batchId);
        jdbc.update("""
                INSERT INTO iktv_song_categories (song_id, snapshot_version, reference_song_id, category)
                SELECT publication.song_id, publication.snapshot_version,
                       (entry.value ->> 'reference_song_id')::bigint, entry.value ->> 'category'
                  FROM iktv_song_match_batch_publications publication
                  JOIN songs song ON song.id = publication.song_id
                  CROSS JOIN LATERAL jsonb_array_elements(publication.previous_categories) entry(value)
                 WHERE publication.batch_id = ? AND publication.rollback_categories_eligible
                ON CONFLICT DO NOTHING
                """,
                batchId);
    }

    public RollbackResult resume() {
        return transaction.execute(status -> {
            String[] paused = jdbc.query("""
                    SELECT paused_snapshot_version, paused_by_batch_id
                      FROM iktv_reference_fusion_control WHERE singleton_id = 1 FOR UPDATE
                    """, rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null);
            if (paused == null || paused[0] == null) {
                return new RollbackResult(0, "NOT_PAUSED", 0, 0, 0, 0);
            }
            Long batchId = paused[1] == null ? null : Long.valueOf(paused[1]);
            jdbc.update("""
                    UPDATE iktv_reference_fusion_control
                       SET paused_snapshot_version = NULL, paused_by_batch_id = NULL,
                           pause_reason = NULL, paused_at = NULL, updated_at = now()
                     WHERE singleton_id = 1
                    """);
            if (batchId != null) {
                jdbc.update("""
                        INSERT INTO iktv_song_match_work (song_id, generation, batch_id, state,
                                                          available_at, lease_token, lease_until,
                                                          requested_at, last_error_code)
                        SELECT item.song_id, 1, NULL, 'PENDING', now(), NULL, NULL, now(), NULL
                          FROM iktv_song_match_batch_items item
                          JOIN songs song ON song.id = item.song_id
                         WHERE item.batch_id = ?
                        ON CONFLICT (song_id) DO UPDATE SET
                            generation = iktv_song_match_work.generation + 1,
                            state = 'PENDING', available_at = now(), lease_token = NULL, lease_until = NULL,
                            requested_at = now(), last_error_code = NULL
                        """, batchId);
            }
            return new RollbackResult(batchId == null ? 0 : batchId, "RESUMED", 0, 0, 0, 0);
        });
    }

    public FusionStatus status() {
        String active = jdbc.query("SELECT snapshot_version FROM iktv_reference_snapshots WHERE status = 'ACTIVE'",
                rs -> rs.next() ? rs.getString(1) : null);
        String[] paused = jdbc.query("""
                SELECT paused_snapshot_version, paused_by_batch_id, pause_reason, paused_at
                  FROM iktv_reference_fusion_control WHERE singleton_id = 1
                """, rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4)} : null);
        return new FusionStatus(active, paused == null ? null : paused[0],
                paused == null || paused[1] == null ? null : Long.valueOf(paused[1]),
                paused == null ? null : paused[2], paused == null ? null : paused[3]);
    }

    public BatchStatus batchStatus(long batchId) {
        BatchStatus batch = jdbc.query("""
                SELECT batch.id, batch.batch_kind, batch.snapshot_version, batch.state,
                       batch.publication_state, batch.total_count, batch.processed_count,
                       batch.matched_count, batch.unmatched_count, batch.conflict_count,
                       batch.protected_count, batch.out_of_scope_count, batch.failed_count,
                       batch.classification_count, batch.last_error_code, batch.created_at,
                       batch.started_at, batch.completed_at, batch.rollback_completed_at,
                       (SELECT COUNT(*) FROM iktv_song_match_batch_publications publication
                         WHERE publication.batch_id = batch.id AND publication.rollback_conflict_code IS NOT NULL)
                           AS rollback_conflict_count
                  FROM iktv_song_match_batches batch WHERE batch.id = ?
                """, rs -> rs.next() ? new BatchStatus(rs.getLong("id"), rs.getString("batch_kind"),
                rs.getString("snapshot_version"), rs.getString("state"), rs.getString("publication_state"),
                rs.getLong("total_count"), rs.getLong("processed_count"), rs.getLong("matched_count"),
                rs.getLong("unmatched_count"), rs.getLong("conflict_count"), rs.getLong("protected_count"),
                rs.getLong("out_of_scope_count"), rs.getLong("failed_count"), rs.getLong("classification_count"),
                rs.getString("last_error_code"), rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("started_at", java.time.OffsetDateTime.class),
                rs.getObject("completed_at", java.time.OffsetDateTime.class),
                rs.getObject("rollback_completed_at", java.time.OffsetDateTime.class),
                rs.getLong("rollback_conflict_count")) : null, batchId);
        if (batch == null) throw new IktvFusionException("IKTV_BATCH_NOT_FOUND", "融合批次不存在");
        return batch;
    }

    private record Batch(long id, String snapshotVersion, String state, String publicationState, long totalCount) {}
    private record BatchGate(long itemCount, long failedCount, long invalidCount) {}
    private record BatchCounts(long processed, long matched, long unmatched, long conflicts,
                               long protectedCount, long outOfScope, long classificationChanges,
                               long catalogChanges) {}

    public record FusionStatus(String activeSnapshotVersion, String pausedSnapshotVersion,
                               Long pausedByBatchId, String pauseReason, String pausedAt) {}

    public record BatchStatus(long id, String batchKind, String snapshotVersion, String state,
                              String publicationState, long totalCount, long processedCount,
                              long matchedCount, long unmatchedCount, long conflictCount,
                              long protectedCount, long outOfScopeCount, long failedCount,
                              long classificationCount, String lastErrorCode,
                              java.time.OffsetDateTime createdAt, java.time.OffsetDateTime startedAt,
                              java.time.OffsetDateTime completedAt, java.time.OffsetDateTime rollbackCompletedAt,
                              long rollbackConflictCount) {}

    public record RollbackResult(long batchId, String state, long catalogNumbersChanged,
                                 long categoriesChanged, long conflictingCategoriesOrNumbers,
                                 long conflictRows) {}
}
