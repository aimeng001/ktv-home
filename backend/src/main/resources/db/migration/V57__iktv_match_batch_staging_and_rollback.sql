-- Keep reference matching proposals separate from Home song/category state until
-- the whole batch has passed its completeness and active-snapshot gates.
ALTER TABLE iktv_song_match_batches
    ADD COLUMN publication_state VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNTRACKED',
    ADD COLUMN rollback_completed_at TIMESTAMPTZ;

ALTER TABLE iktv_song_match_batches
    ADD CONSTRAINT ck_iktv_song_match_publication_state CHECK (publication_state IN (
        'STAGED', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'ROLLED_BACK',
        'ROLLBACK_PARTIAL', 'LEGACY_UNTRACKED'
    ));

UPDATE iktv_song_match_batches
   SET publication_state = 'LEGACY_UNTRACKED';
ALTER TABLE iktv_song_match_batches ALTER COLUMN publication_state SET DEFAULT 'STAGED';

ALTER TABLE iktv_song_match_batch_items
    ADD COLUMN input_catalog_number VARCHAR(10) NOT NULL DEFAULT '',
    ADD COLUMN input_title TEXT NOT NULL DEFAULT '',
    ADD COLUMN input_artist TEXT NOT NULL DEFAULT '',
    ADD COLUMN input_metadata_locks TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN proposed_catalog_number VARCHAR(10) NOT NULL DEFAULT '';

ALTER TABLE iktv_song_match_batch_items
    ADD CONSTRAINT ck_iktv_batch_item_proposed_catalog_number
        CHECK (proposed_catalog_number = '' OR proposed_catalog_number ~ '^[0-9]{8,10}$');

CREATE TABLE iktv_song_match_batch_item_categories (
    batch_id BIGINT NOT NULL,
    song_id BIGINT NOT NULL,
    snapshot_version VARCHAR(64) NOT NULL,
    reference_song_id BIGINT NOT NULL,
    category TEXT NOT NULL,
    PRIMARY KEY (batch_id, song_id, reference_song_id, category),
    FOREIGN KEY (batch_id, song_id)
        REFERENCES iktv_song_match_batch_items(batch_id, song_id) ON DELETE CASCADE,
    FOREIGN KEY (snapshot_version, reference_song_id)
        REFERENCES iktv_reference_songs(snapshot_version, reference_song_id) ON DELETE CASCADE
);

CREATE INDEX idx_iktv_match_item_categories_batch
    ON iktv_song_match_batch_item_categories (batch_id, song_id);

-- One durable before/after record per eligible item permits a guarded rollback.
-- JSONB preserves the full category set, including an empty set, without tying
-- history lifetime to pruning a retired reference snapshot.
CREATE TABLE iktv_song_match_batch_publications (
    batch_id BIGINT NOT NULL,
    song_id BIGINT NOT NULL,
    snapshot_version VARCHAR(64) NOT NULL,
    previous_catalog_number VARCHAR(10) NOT NULL DEFAULT '',
    applied_catalog_number VARCHAR(10) NOT NULL DEFAULT '',
    catalog_number_written BOOLEAN NOT NULL DEFAULT false,
    previous_categories JSONB NOT NULL DEFAULT '[]'::jsonb,
    applied_categories JSONB NOT NULL DEFAULT '[]'::jsonb,
    categories_written BOOLEAN NOT NULL DEFAULT false,
    rollback_categories_eligible BOOLEAN NOT NULL DEFAULT false,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    rolled_back_at TIMESTAMPTZ,
    rollback_conflict_code VARCHAR(64),
    PRIMARY KEY (batch_id, song_id),
    FOREIGN KEY (batch_id, song_id)
        REFERENCES iktv_song_match_batch_items(batch_id, song_id) ON DELETE CASCADE,
    CHECK (jsonb_typeof(previous_categories) = 'array'),
    CHECK (jsonb_typeof(applied_categories) = 'array')
);

CREATE INDEX idx_iktv_match_publications_batch
    ON iktv_song_match_batch_publications (batch_id, song_id);

CREATE TABLE iktv_reference_fusion_control (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    paused_snapshot_version VARCHAR(64),
    paused_by_batch_id BIGINT REFERENCES iktv_song_match_batches(id) ON DELETE SET NULL,
    pause_reason VARCHAR(64),
    paused_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO iktv_reference_fusion_control (singleton_id)
VALUES (1)
ON CONFLICT (singleton_id) DO NOTHING;

-- Invalidate staged decisions whenever a committed Home identity or source-path
-- change is recorded. The out-of-scope status for a deleted song is preserved
-- even after its work-queue row was consumed.
CREATE OR REPLACE FUNCTION enqueue_iktv_song_match(p_song_id BIGINT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF p_song_id IS NULL OR current_setting('homektv.reference_fusion', true) = 'on' THEN
        RETURN;
    END IF;

    UPDATE iktv_song_match_batch_items item
       SET match_status = 'STALE_GENERATION',
           reason_code = 'HOME_SOURCE_CHANGED_AFTER_STAGING',
           processed_at = now()
      FROM iktv_song_match_batches batch
     WHERE item.batch_id = batch.id
       AND item.song_id = p_song_id
       AND batch.state IN ('PENDING', 'RUNNING')
       AND batch.publication_state = 'STAGED';

    INSERT INTO iktv_song_match_work (song_id, generation, state, requested_at)
    VALUES (p_song_id, 1, 'PENDING', now())
    ON CONFLICT (song_id) DO UPDATE
       SET generation = iktv_song_match_work.generation + 1,
           state = 'PENDING',
           available_at = now(),
           lease_token = NULL,
           lease_until = NULL,
           requested_at = now(),
           last_error_code = NULL;
END;
$$;

CREATE OR REPLACE FUNCTION trg_record_iktv_song_match_out_of_scope()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    pending_batch_id BIGINT;
BEGIN
    UPDATE iktv_song_match_batch_items item
       SET match_status = 'OUT_OF_SCOPE',
           reason_code = 'SONG_DELETED_DURING_BATCH',
           processed_at = now()
      FROM iktv_song_match_batches batch
     WHERE item.batch_id = batch.id
       AND item.song_id = OLD.id
       AND batch.state IN ('PENDING', 'RUNNING')
       AND batch.publication_state = 'STAGED';

    SELECT batch_id INTO pending_batch_id
      FROM iktv_song_match_work
     WHERE song_id = OLD.id;
    IF pending_batch_id IS NOT NULL THEN
        INSERT INTO iktv_song_match_batch_items (
            batch_id, song_id, generation, match_status, reason_code, processed_at
        )
        SELECT pending_batch_id, OLD.id, generation, 'OUT_OF_SCOPE', 'SONG_DELETED_DURING_BATCH', now()
          FROM iktv_song_match_work
         WHERE song_id = OLD.id
        ON CONFLICT (batch_id, song_id) DO UPDATE SET
            generation = EXCLUDED.generation,
            match_status = EXCLUDED.match_status,
            reason_code = EXCLUDED.reason_code,
            processed_at = EXCLUDED.processed_at;
    END IF;
    RETURN OLD;
END;
$$;

-- A pre-V57 in-flight batch may already have written a subset of Home metadata,
-- but its rows do not contain the identity/category snapshots required by the
-- new publication gate. Revert only unlocked catalog numbers that still equal
-- the old worker's recorded output, and requeue the whole affected item set into
-- a fresh staged batch. Keep the old derived categories until that new batch
-- safely replaces them.
SELECT set_config('homektv.reference_fusion', 'on', true);

UPDATE songs song
   SET catalog_number = item.previous_catalog_number
  FROM iktv_song_match_batch_items item
  JOIN iktv_song_match_batches batch ON batch.id = item.batch_id
 WHERE batch.state IN ('PENDING', 'RUNNING', 'FAILED')
   AND item.catalog_number_changed
   AND song.id = item.song_id
   AND NOT (song.metadata_locks @> ARRAY['catalogNumber']::text[])
   AND NOT (song.metadata_locks @> ARRAY['catalog_number']::text[])
   AND COALESCE(song.catalog_number, '') = COALESCE(item.applied_catalog_number, '');

INSERT INTO iktv_song_match_work (
    song_id, generation, batch_id, state, attempts, available_at, lease_token,
    lease_until, requested_at, last_error_code
)
SELECT DISTINCT legacy.song_id, 1, NULL::BIGINT, 'PENDING', 0, now(), NULL::UUID,
                NULL::TIMESTAMPTZ, now(), NULL::VARCHAR
  FROM (
        SELECT item.song_id
          FROM iktv_song_match_batch_items item
          JOIN iktv_song_match_batches batch ON batch.id = item.batch_id
         WHERE batch.state IN ('PENDING', 'RUNNING', 'FAILED')
        UNION
        SELECT work.song_id
          FROM iktv_song_match_work work
          JOIN iktv_song_match_batches batch ON batch.id = work.batch_id
         WHERE batch.state IN ('PENDING', 'RUNNING', 'FAILED')
  ) legacy
ON CONFLICT (song_id) DO UPDATE SET
    generation = iktv_song_match_work.generation + 1,
    batch_id = NULL::BIGINT,
    state = 'PENDING',
    attempts = 0,
    available_at = now(),
    lease_token = NULL,
    lease_until = NULL,
    requested_at = now(),
    last_error_code = NULL;

UPDATE iktv_song_match_batches
   SET state = 'FAILED',
       publication_state = 'LEGACY_UNTRACKED',
       last_error_code = 'LEGACY_BATCH_REQUEUED_FOR_SAFE_STAGING',
       completed_at = now()
 WHERE state IN ('PENDING', 'RUNNING', 'FAILED');
