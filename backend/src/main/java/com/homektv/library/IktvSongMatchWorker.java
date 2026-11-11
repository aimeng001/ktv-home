package com.homektv.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;

/** Persistent, bounded worker for initial and incremental Home-song matching. */
@Component
public final class IktvSongMatchWorker {
    private static final Logger log = LoggerFactory.getLogger(IktvSongMatchWorker.class);
    private static final int CLAIM_SIZE = 100;
    private static final int MAX_CANDIDATES = 500;
    private static final int MAX_ITEMS_PER_TICK = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IktvSongReferenceMatcher matcher;
    private final IktvMetadataEnrichmentService metadataEnrichmentService;
    private final IktvSongMatchBatchService batchService;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public IktvSongMatchWorker(JdbcTemplate jdbc,
                               PlatformTransactionManager transactionManager,
                               IktvSongReferenceMatcher matcher,
                               IktvMetadataEnrichmentService metadataEnrichmentService,
                               IktvSongMatchBatchService batchService,
                               @Qualifier("iktvReferenceCatalogExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.matcher = matcher;
        this.metadataEnrichmentService = metadataEnrichmentService;
        this.batchService = batchService;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${app.iktv-reference.match-delay-ms:1000}",
            initialDelayString = "${app.iktv-reference.match-initial-delay-ms:45000}")
    public void processScheduledWork() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    drainWork();
                } catch (Exception exception) {
                    log.error("IKTV Home-song matching cycle failed; queued work remains durable", exception);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            running.set(false);
            log.warn("IKTV Home-song matching was not scheduled: {}", rejected.getClass().getSimpleName());
        }
    }

    private void drainWork() {
        ensureWorkBatches();
        int processed = 0;
        while (processed < MAX_ITEMS_PER_TICK) {
            List<ClaimedSong> claimed = claim(CLAIM_SIZE);
            if (claimed.isEmpty()) break;
            for (ClaimedSong item : claimed) {
                processClaimed(item);
                processed++;
            }
        }
        completeDrainedBatches();
        if (processed > 0) log.info("IKTV Home-song matching processed {} queue items", processed);
    }

    private void ensureWorkBatches() {
        String activeVersion = activeSnapshotVersion();
        if (activeVersion == null) return;
        transaction.executeWithoutResult(status -> {
            Long initialId = nullableId("SELECT id FROM iktv_song_match_batches WHERE batch_kind = 'INITIAL'");
            if (initialId == null) {
                Long total = jdbc.queryForObject("SELECT COUNT(*) FROM songs", Long.class);
                long initialCount = total == null ? 0 : total;
                Long createdId = jdbc.query("""
                        INSERT INTO iktv_song_match_batches (batch_kind, snapshot_version, state, total_count)
                        VALUES ('INITIAL', ?, ?, ?)
                        ON CONFLICT DO NOTHING
                        RETURNING id
                        """, rs -> rs.next() ? rs.getLong(1) : null,
                        activeVersion, "PENDING", initialCount);
                if (createdId == null) return;
                int queued = jdbc.update("""
                        INSERT INTO iktv_song_match_work (song_id, generation, batch_id, state)
                        SELECT id, 1, ?, 'PENDING' FROM songs ORDER BY id
                        ON CONFLICT (song_id) DO UPDATE SET
                            generation = iktv_song_match_work.generation + 1,
                            batch_id = COALESCE(iktv_song_match_work.batch_id, EXCLUDED.batch_id),
                            state = 'PENDING', available_at = now(), lease_token = NULL, lease_until = NULL,
                            requested_at = now(), last_error_code = NULL
                        """, createdId);
                if (queued != initialCount) {
                    throw new IllegalStateException("INITIAL_BATCH_QUEUE_COUNT_MISMATCH");
                }
            }

            ensureSnapshotReconciliation(activeVersion);
            reassignStaleStagedWork(activeVersion);

            Long unassigned = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM iktv_song_match_work WHERE batch_id IS NULL
                    """, Long.class);
            if (unassigned != null && unassigned > 0) {
                Long incrementalId = jdbc.queryForObject("""
                        INSERT INTO iktv_song_match_batches (batch_kind, snapshot_version, state, total_count)
                        VALUES ('INCREMENTAL', ?, 'PENDING', ?)
                        RETURNING id
                        """, Long.class, activeVersion, unassigned);
                if (incrementalId == null) throw new IllegalStateException("INCREMENTAL_BATCH_CREATE_FAILED");
                int assigned = jdbc.update("""
                        UPDATE iktv_song_match_work SET batch_id = ?
                         WHERE batch_id IS NULL
                        """, incrementalId);
                if (assigned != unassigned) throw new IllegalStateException("INCREMENTAL_BATCH_ASSIGN_COUNT_MISMATCH");
            }
        });
    }

    private String activeSnapshotVersion() {
        return jdbc.query("""
                SELECT snapshot.snapshot_version
                  FROM iktv_reference_snapshots snapshot
                 WHERE snapshot.status = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM iktv_reference_fusion_control control
                        WHERE control.singleton_id = 1
                          AND control.paused_snapshot_version = snapshot.snapshot_version
                   )
                """, resultSet -> resultSet.next() ? resultSet.getString(1) : null);
    }

    private void ensureSnapshotReconciliation(String activeVersion) {
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_batches
                 WHERE batch_kind = 'SNAPSHOT' AND snapshot_version = ?
                """, Integer.class, activeVersion);
        if (existing != null && existing > 0) return;
        String previousVersion = jdbc.query("""
                SELECT snapshot_version FROM iktv_reference_snapshots
                 WHERE status = 'RETIRED' AND activated_at IS NOT NULL
                 ORDER BY activated_at DESC LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null);
        if (previousVersion == null || previousVersion.equals(activeVersion)) return;

        Long batchId = jdbc.query("""
                INSERT INTO iktv_song_match_batches (batch_kind, snapshot_version, state, total_count)
                VALUES ('SNAPSHOT', ?, 'PENDING', 0)
                ON CONFLICT DO NOTHING
                RETURNING id
                """, rs -> rs.next() ? rs.getLong(1) : null, activeVersion);
        if (batchId == null) return;

        jdbc.update("""
                WITH changed AS (
                    (SELECT reference_song_id, catalog_number, normalized_title, normalized_artist,
                            normalized_edition, edition, language, category, song_type,
                            special_tags, special_tag_conflict
                       FROM iktv_reference_songs WHERE snapshot_version = ?
                     EXCEPT
                     SELECT reference_song_id, catalog_number, normalized_title, normalized_artist,
                            normalized_edition, edition, language, category, song_type,
                            special_tags, special_tag_conflict
                       FROM iktv_reference_songs WHERE snapshot_version = ?)
                    UNION
                    (SELECT reference_song_id, catalog_number, normalized_title, normalized_artist,
                            normalized_edition, edition, language, category, song_type,
                            special_tags, special_tag_conflict
                       FROM iktv_reference_songs WHERE snapshot_version = ?
                     EXCEPT
                     SELECT reference_song_id, catalog_number, normalized_title, normalized_artist,
                            normalized_edition, edition, language, category, song_type,
                            special_tags, special_tag_conflict
                       FROM iktv_reference_songs WHERE snapshot_version = ?)
                ), changed_ids AS (
                    SELECT DISTINCT reference_song_id FROM changed
                ), changed_numbers AS (
                    SELECT DISTINCT catalog_number FROM changed WHERE catalog_number <> ''
                )
                INSERT INTO iktv_song_match_work (song_id, generation, batch_id, state)
                SELECT DISTINCT result.song_id, 1, ?, 'PENDING'
                  FROM iktv_song_match_results result
                 WHERE (
                       (result.snapshot_version <> ? AND result.snapshot_version <> ?)
                       OR result.reference_song_id IN (SELECT reference_song_id FROM changed_ids)
                       OR EXISTS (
                           SELECT 1 FROM changed changed_key
                            WHERE changed_key.normalized_title = result.normalized_title
                              AND changed_key.normalized_artist = result.normalized_artist
                       )
                       OR result.home_catalog_number IN (SELECT catalog_number FROM changed_numbers)
                       OR result.number_clues && ARRAY(SELECT catalog_number FROM changed_numbers)
                   )
                ON CONFLICT (song_id) DO UPDATE SET
                    generation = iktv_song_match_work.generation + 1,
                    batch_id = EXCLUDED.batch_id,
                    state = 'PENDING', available_at = now(), lease_token = NULL, lease_until = NULL,
                    requested_at = now(), last_error_code = NULL
                """, previousVersion, activeVersion, activeVersion, previousVersion,
                batchId, previousVersion, activeVersion);
        jdbc.update("""
                UPDATE iktv_song_match_batches SET state = 'REVOKED', completed_at = now()
                 WHERE snapshot_version = ? AND state IN ('PENDING', 'RUNNING') AND id <> ?
                """, previousVersion, batchId);
        jdbc.update("""
                UPDATE iktv_song_match_work
                   SET generation = generation + 1, batch_id = ?, state = 'PENDING',
                       available_at = now(), lease_token = NULL, lease_until = NULL, requested_at = now()
                 WHERE batch_id IS NOT NULL AND batch_id <> ?
                """, batchId, batchId);
        Long affectedCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_work WHERE batch_id = ?
                """, Long.class, batchId);
        long count = affectedCount == null ? 0 : affectedCount;
        jdbc.update("""
                UPDATE iktv_song_match_batches
                   SET total_count = ?, state = ?, completed_at = CASE WHEN ? = 0 THEN now() ELSE NULL END
                 WHERE id = ?
                """, count, count == 0 ? "COMPLETED" : "PENDING", count, batchId);
    }

    private void reassignStaleStagedWork(String activeVersion) {
        jdbc.update("""
                WITH reassignable AS (
                    SELECT work.song_id,
                           (
                               SELECT item.batch_id
                                 FROM iktv_song_match_batch_items item
                                 JOIN iktv_song_match_batches batch ON batch.id = item.batch_id
                                WHERE item.song_id = work.song_id
                                  AND batch.snapshot_version = ?
                                  AND batch.state IN ('PENDING', 'RUNNING')
                                  AND batch.publication_state = 'STAGED'
                                ORDER BY item.batch_id DESC
                                LIMIT 1
                           ) AS target_batch_id
                      FROM iktv_song_match_work work
                     WHERE work.batch_id IS NULL
                )
                UPDATE iktv_song_match_work work
                   SET batch_id = reassignable.target_batch_id
                  FROM reassignable
                 WHERE work.song_id = reassignable.song_id
                   AND reassignable.target_batch_id IS NOT NULL
                """, activeVersion);
    }

    private List<ClaimedSong> claim(int limit) {
        String token = UUID.randomUUID().toString();
        return transaction.execute(status -> {
            List<ClaimedSong> claimed = jdbc.query("""
                WITH selected AS (
                    SELECT song_id
                      FROM iktv_song_match_work
                     WHERE batch_id IS NOT NULL
                       AND available_at <= now()
                       AND (state = 'PENDING' OR lease_until < now())
                       AND NOT EXISTS (
                           SELECT 1
                             FROM iktv_song_match_batches batch
                             JOIN iktv_reference_fusion_control control ON control.singleton_id = 1
                            WHERE batch.id = iktv_song_match_work.batch_id
                              AND batch.snapshot_version = control.paused_snapshot_version
                       )
                     ORDER BY requested_at, song_id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE iktv_song_match_work work
                   SET state = 'RUNNING', lease_token = ?::uuid,
                       lease_until = now() + interval '2 minutes'
                  FROM selected
                 WHERE work.song_id = selected.song_id
                RETURNING work.song_id, work.generation, work.batch_id
                """, (rs, rowNum) -> new ClaimedSong(rs.getLong("song_id"), rs.getLong("generation"),
                rs.getLong("batch_id"), token), limit, token);
            claimed.stream().map(ClaimedSong::batchId).distinct().forEach(batchId -> jdbc.update("""
                    UPDATE iktv_song_match_batches
                       SET state = 'RUNNING', started_at = COALESCE(started_at, now())
                     WHERE id = ? AND state = 'PENDING'
                    """, batchId));
            return claimed;
        });
    }

    private void processClaimed(ClaimedSong claimed) {
        try {
            transaction.executeWithoutResult(status -> processOne(claimed));
        } catch (Exception exception) {
            recordFailure(claimed, exception);
        }
    }

    private void processOne(ClaimedSong claim) {
        String batchSnapshot = jdbc.query("""
                SELECT snapshot_version FROM iktv_song_match_batches WHERE id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, claim.batchId());
        if (batchService.isPaused(batchSnapshot)) {
            jdbc.update("""
                    UPDATE iktv_song_match_work
                       SET state = 'PENDING', lease_token = NULL, lease_until = NULL, available_at = now()
                     WHERE song_id = ? AND generation = ? AND lease_token = ?::uuid
                    """, claim.songId(), claim.generation(), claim.leaseToken());
            return;
        }
        HomeRow homeRow = jdbc.query("""
                SELECT id, catalog_number, title, artist, metadata_locks
                  FROM songs WHERE id = ? FOR UPDATE
                """, rs -> rs.next() ? mapHome(rs) : null, claim.songId());
        if (homeRow == null) {
            jdbc.update("DELETE FROM iktv_song_match_work WHERE song_id = ? AND lease_token = ?::uuid",
                    claim.songId(), claim.leaseToken());
            return;
        }
        WorkRow work = jdbc.query("""
                SELECT generation, batch_id, lease_token
                  FROM iktv_song_match_work WHERE song_id = ? FOR UPDATE
                """, rs -> rs.next() ? new WorkRow(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null,
                claim.songId());
        if (work == null || !claim.leaseToken().equals(work.leaseToken())) return;

        String snapshotVersion = jdbc.query("""
                SELECT snapshot_version FROM iktv_reference_snapshots
                 WHERE status = 'ACTIVE' FOR SHARE
                """, rs -> rs.next() ? rs.getString(1) : null);
        if (snapshotVersion == null) {
            throw new IllegalStateException("ACTIVE_REFERENCE_SNAPSHOT_MISSING");
        }

        List<IktvSongReferenceMatcher.SourcePath> paths = jdbc.query("""
                SELECT COALESCE(relative_path, ''), file_path
                  FROM song_files
                 WHERE song_id = ? AND valid = true AND file_role = 'LIBRARY' AND source_deleted = false
                 ORDER BY priority DESC, id
                """, (rs, rowNum) -> new IktvSongReferenceMatcher.SourcePath(rs.getString(1), rs.getString(2)),
                claim.songId());
        IktvSongReferenceMatcher.HomeSong input = new IktvSongReferenceMatcher.HomeSong(
                homeRow.catalogNumber(), homeRow.title(), homeRow.artist(), "", "", homeRow.metadataLocks(), paths);
        CandidateSet candidates = findCandidates(snapshotVersion, input);
        IktvSongReferenceMatcher.Result match = candidates.tooMany()
                ? new IktvSongReferenceMatcher.Result(
                        IktvSongReferenceMatcher.Status.AMBIGUOUS_VERSION,
                        "REFERENCE_CANDIDATE_LIMIT_EXCEEDED",
                        IktvSongReferenceMatcher.catalogNumberClues(input).isEmpty()
                                ? IktvSongReferenceMatcher.Method.TITLE_AND_ARTIST
                                : IktvSongReferenceMatcher.Method.CATALOG_NUMBER,
                        null, "", candidates.candidateCount())
                : matcher.match(input, candidates.references());

        String matchStatus = match.status().name();
        String reasonCode = match.reason();
        String previousNumber = homeRow.catalogNumber();
        String proposedNumber = "";
        if (match.status() == IktvSongReferenceMatcher.Status.MATCHED && match.candidate() != null
                && previousNumber.isBlank() && !match.candidate().catalogNumber().isBlank()) {
            if (isCatalogNumberLocked(homeRow.metadataLocks())) {
                matchStatus = "PROTECTED_EXISTING_VALUE";
                reasonCode = "CATALOG_NUMBER_METADATA_LOCKED";
            } else {
                proposedNumber = match.candidate().catalogNumber();
            }
        }

        String[] clues = IktvSongReferenceMatcher.catalogNumberClues(input).stream().sorted().toArray(String[]::new);
        String normalizedTitle = IktvSongReferenceMatcher.normalize(homeRow.title());
        String normalizedArtist = IktvSongReferenceMatcher.normalize(homeRow.artist());
        persistResult(claim, snapshotVersion, input, match, matchStatus, reasonCode, clues,
                normalizedTitle, normalizedArtist, previousNumber, null, false);
        persistBatchItem(claim, match, matchStatus, reasonCode, homeRow, proposedNumber);
        stageReferenceCategories(claim.batchId(), claim.songId(), snapshotVersion,
                match.status() == IktvSongReferenceMatcher.Status.MATCHED ? match.candidate() : null);
        jdbc.update("""
                DELETE FROM iktv_song_match_work
                 WHERE song_id = ? AND generation = ? AND lease_token = ?::uuid
                """, claim.songId(), work.generation(), claim.leaseToken());
    }

    private void stageReferenceCategories(long batchId, long songId, String snapshotVersion,
                                          IktvSongReferenceMatcher.ReferenceSong referenceSong) {
        List<IktvReferenceClassificationPolicy.CategoryAssignment> target =
                metadataEnrichmentService.getCategoryAssignmentsForMatchedReference(referenceSong);
        if (referenceSong == null && !target.isEmpty()) {
            throw new IllegalStateException("REFERENCE_CATEGORIES_WITHOUT_MATCHED_RECORD");
        }
        jdbc.update("DELETE FROM iktv_song_match_batch_item_categories WHERE batch_id = ? AND song_id = ?",
                batchId, songId);
        if (!target.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO iktv_song_match_batch_item_categories (
                        batch_id, song_id, snapshot_version, reference_song_id, category
                    ) VALUES (?, ?, ?, ?, ?)
                    """, target, target.size(), (statement, assignment) -> {
                statement.setLong(1, batchId);
                statement.setLong(2, songId);
                statement.setString(3, snapshotVersion);
                statement.setLong(4, assignment.referenceSongId());
                statement.setString(5, assignment.category());
            });
        }
    }

    private CandidateSet findCandidates(
            String snapshotVersion, IktvSongReferenceMatcher.HomeSong home) {
        Set<String> clues = IktvSongReferenceMatcher.catalogNumberClues(home);
        if (clues.size() > 1) return new CandidateSet(List.of(), 0, false);
        String number = clues.stream().findFirst().orElse("");
        String normalizedTitle = IktvSongReferenceMatcher.normalize(home.title());
        String normalizedArtist = IktvSongReferenceMatcher.normalize(home.artist());
        String where = number.isBlank()
                ? "normalized_title = ? AND normalized_artist = ?"
                : "catalog_number = ?";
        Object[] parameters = number.isBlank()
                ? new Object[]{snapshotVersion, normalizedTitle, normalizedArtist}
                : new Object[]{snapshotVersion, number};
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM iktv_reference_songs WHERE snapshot_version = ? AND "
                + where, Integer.class, parameters);
        if (count == null || count == 0) return new CandidateSet(List.of(), 0, false);
        if (count > MAX_CANDIDATES) return new CandidateSet(List.of(), count, true);
        List<IktvSongReferenceMatcher.ReferenceSong> references = jdbc.query("""
                SELECT reference_song_id, catalog_number, title, artist_credit, edition, language,
                       category, song_type, special_tags, special_tag_conflict
                  FROM iktv_reference_songs
                 WHERE snapshot_version = ? AND
                """ + where + " ORDER BY reference_song_id",
                (rs, rowNum) -> mapReference(rs), parameters);
        return new CandidateSet(references, count, false);
    }

    private void persistResult(ClaimedSong claim, String version,
                               IktvSongReferenceMatcher.HomeSong input,
                               IktvSongReferenceMatcher.Result match, String status, String reason,
                               String[] clues, String normalizedTitle, String normalizedArtist,
                               String previous, String applied, boolean changed) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO iktv_song_match_results (
                        song_id, snapshot_version, batch_id, reference_song_id, matching_method,
                        match_status, reason_code, candidate_count, home_catalog_number, number_clues,
                        normalized_title, normalized_artist, catalog_number_changed,
                        previous_catalog_number, applied_catalog_number, matched_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (song_id) DO UPDATE SET
                        snapshot_version = EXCLUDED.snapshot_version,
                        batch_id = EXCLUDED.batch_id,
                        reference_song_id = EXCLUDED.reference_song_id,
                        matching_method = EXCLUDED.matching_method,
                        match_status = EXCLUDED.match_status,
                        reason_code = EXCLUDED.reason_code,
                        candidate_count = EXCLUDED.candidate_count,
                        home_catalog_number = EXCLUDED.home_catalog_number,
                        number_clues = EXCLUDED.number_clues,
                        normalized_title = EXCLUDED.normalized_title,
                        normalized_artist = EXCLUDED.normalized_artist,
                        catalog_number_changed = EXCLUDED.catalog_number_changed,
                        previous_catalog_number = EXCLUDED.previous_catalog_number,
                        applied_catalog_number = EXCLUDED.applied_catalog_number,
                        matched_at = now()
                    """);
            statement.setLong(1, claim.songId());
            statement.setString(2, version);
            statement.setLong(3, claim.batchId());
            if (match.candidate() == null) statement.setNull(4, java.sql.Types.BIGINT);
            else statement.setLong(4, match.candidate().referenceSongId());
            statement.setString(5, match.method().name());
            statement.setString(6, status);
            statement.setString(7, reason);
            statement.setInt(8, match.candidateCount());
            statement.setString(9, input.catalogNumber());
            statement.setArray(10, connection.createArrayOf("text", clues));
            statement.setString(11, normalizedTitle);
            statement.setString(12, normalizedArtist);
            statement.setBoolean(13, changed);
            if (changed) statement.setString(14, previous); else statement.setNull(14, java.sql.Types.VARCHAR);
            if (changed) statement.setString(15, applied); else statement.setNull(15, java.sql.Types.VARCHAR);
            return statement;
        });
    }

    private void persistBatchItem(ClaimedSong claim, IktvSongReferenceMatcher.Result match,
                                  String status, String reason, HomeRow home, String proposedNumber) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO iktv_song_match_batch_items (
                        batch_id, song_id, generation, match_status, reason_code, reference_song_id,
                        catalog_number_changed, previous_catalog_number, applied_catalog_number,
                        classification_changed, processed_at, input_catalog_number, input_title,
                        input_artist, input_metadata_locks, proposed_catalog_number
                    ) VALUES (?, ?, ?, ?, ?, ?, false, NULL, NULL, false, now(), ?, ?, ?, ?, ?)
                    ON CONFLICT (batch_id, song_id) DO UPDATE SET
                        generation = EXCLUDED.generation,
                        match_status = EXCLUDED.match_status,
                        reason_code = EXCLUDED.reason_code,
                        reference_song_id = EXCLUDED.reference_song_id,
                        catalog_number_changed = false,
                        previous_catalog_number = NULL,
                        applied_catalog_number = NULL,
                        classification_changed = false,
                        input_catalog_number = EXCLUDED.input_catalog_number,
                        input_title = EXCLUDED.input_title,
                        input_artist = EXCLUDED.input_artist,
                        input_metadata_locks = EXCLUDED.input_metadata_locks,
                        proposed_catalog_number = EXCLUDED.proposed_catalog_number,
                        processed_at = now()
                    """);
            statement.setLong(1, claim.batchId());
            statement.setLong(2, claim.songId());
            statement.setLong(3, claim.generation());
            statement.setString(4, status);
            statement.setString(5, reason);
            if (match.candidate() == null) statement.setNull(6, java.sql.Types.BIGINT);
            else statement.setLong(6, match.candidate().referenceSongId());
            statement.setString(7, home.catalogNumber());
            statement.setString(8, home.title());
            statement.setString(9, home.artist());
            statement.setArray(10, connection.createArrayOf("text", home.metadataLocks().toArray()));
            statement.setString(11, proposedNumber);
            return statement;
        });
    }

    private void completeBatchIfDrained(long batchId) {
        batchService.tryPublishBatch(batchId);
    }

    private void completeDrainedBatches() {
        List<Long> drained = jdbc.query("""
                SELECT batch.id
                  FROM iktv_song_match_batches batch
                 WHERE batch.state IN ('PENDING', 'RUNNING') AND batch.publication_state = 'STAGED'
                   AND NOT EXISTS (SELECT 1 FROM iktv_song_match_work work WHERE work.batch_id = batch.id)
                 ORDER BY batch.id
                 LIMIT 50
                """, (rs, rowNum) -> rs.getLong(1));
        drained.forEach(this::completeBatchIfDrained);
    }

    private void recordFailure(ClaimedSong claim, Exception exception) {
        String reason = exception instanceof IllegalStateException && exception.getMessage() != null
                && exception.getMessage().matches("[A-Z0-9_]{1,64}")
                ? exception.getMessage() : "MATCH_ITEM_PROCESSING_FAILED";
        Integer attempts = jdbc.query("""
                UPDATE iktv_song_match_work
                   SET attempts = attempts + 1,
                       state = 'PENDING',
                       lease_token = NULL,
                       lease_until = NULL,
                       available_at = now() + make_interval(secs => LEAST(3600, power(2, LEAST(attempts + 1, 10))::int)),
                       last_error_code = ?
                 WHERE song_id = ? AND lease_token = ?::uuid
                RETURNING attempts
                """, rs -> rs.next() ? rs.getInt(1) : null, reason, claim.songId(), claim.leaseToken());
        if (attempts != null) {
            log.warn("IKTV song match deferred: songId={}, attempts={}, reason={}",
                    claim.songId(), attempts, reason);
        }
    }

    private Long nullableId(String sql) {
        return jdbc.query(sql, rs -> rs.next() ? rs.getLong(1) : null);
    }

    private static HomeRow mapHome(ResultSet rs) throws java.sql.SQLException {
        Array locksArray = rs.getArray("metadata_locks");
        Set<String> locks = Set.of();
        if (locksArray != null) {
            Object raw = locksArray.getArray();
            if (raw instanceof String[] values) locks = Set.copyOf(Arrays.asList(values));
            else if (raw instanceof Object[] values) {
                locks = Arrays.stream(values).map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
        }
        return new HomeRow(rs.getLong("id"), rs.getString("catalog_number"), rs.getString("title"),
                rs.getString("artist"), locks);
    }

    private static IktvSongReferenceMatcher.ReferenceSong mapReference(ResultSet rs) throws java.sql.SQLException {
        Array tagArray = rs.getArray("special_tags");
        List<String> tags = List.of();
        if (tagArray != null) {
            Object raw = tagArray.getArray();
            if (raw instanceof String[] values) tags = List.copyOf(Arrays.asList(values));
            else if (raw instanceof Object[] values) tags = Arrays.stream(values).map(String::valueOf).toList();
        }
        return new IktvSongReferenceMatcher.ReferenceSong(rs.getLong("reference_song_id"),
                rs.getString("catalog_number"), rs.getString("title"), rs.getString("artist_credit"),
                rs.getString("edition"), rs.getString("language"), rs.getString("category"),
                rs.getString("song_type"), tags, rs.getBoolean("special_tag_conflict"));
    }

    private static boolean isCatalogNumberLocked(Set<String> locks) {
        return locks.contains("catalogNumber") || locks.contains("catalog_number");
    }

    private record ClaimedSong(long songId, long generation, long batchId, String leaseToken) {}
    private record WorkRow(long generation, long batchId, String leaseToken) {}
    private record CandidateSet(List<IktvSongReferenceMatcher.ReferenceSong> references,
                                int candidateCount, boolean tooMany) {}
    private record HomeRow(long id, String catalogNumber, String title, String artist, Set<String> metadataLocks) {
        private HomeRow {
            catalogNumber = catalogNumber == null ? "" : catalogNumber;
            title = title == null ? "" : title;
            artist = artist == null ? "" : artist;
            metadataLocks = metadataLocks == null ? Set.of() : metadataLocks;
        }
    }
}
