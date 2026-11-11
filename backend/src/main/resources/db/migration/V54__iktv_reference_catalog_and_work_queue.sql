-- The bundled IKTV catalog is reference metadata only. It is never inserted
-- into songs and it never carries a playable URL or media availability.
CREATE TABLE iktv_reference_snapshots (
    snapshot_version VARCHAR(64) PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('STAGING', 'ACTIVE', 'RETIRED', 'FAILED')),
    expected_song_count BIGINT NOT NULL CHECK (expected_song_count >= 0),
    loaded_song_count BIGINT NOT NULL DEFAULT 0 CHECK (loaded_song_count >= 0),
    snapshot_sha256 VARCHAR(64) NOT NULL,
    activated_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_iktv_reference_snapshots_active
    ON iktv_reference_snapshots (status) WHERE status = 'ACTIVE';

CREATE TABLE iktv_reference_songs (
    snapshot_version VARCHAR(64) NOT NULL REFERENCES iktv_reference_snapshots(snapshot_version) ON DELETE CASCADE,
    reference_song_id BIGINT NOT NULL,
    catalog_number VARCHAR(10) NOT NULL DEFAULT '',
    title TEXT NOT NULL,
    artist_credit TEXT NOT NULL,
    edition TEXT NOT NULL DEFAULT '',
    language TEXT NOT NULL DEFAULT '',
    category TEXT NOT NULL DEFAULT '',
    song_type TEXT NOT NULL DEFAULT '',
    normalized_title TEXT NOT NULL,
    normalized_artist TEXT NOT NULL,
    normalized_edition TEXT NOT NULL,
    special_tags TEXT[] NOT NULL DEFAULT '{}',
    special_tag_conflict BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (snapshot_version, reference_song_id),
    CHECK (catalog_number = '' OR catalog_number ~ '^[0-9]{8,10}$')
);

CREATE INDEX idx_iktv_reference_number
    ON iktv_reference_songs (snapshot_version, catalog_number);
CREATE INDEX idx_iktv_reference_identity
    ON iktv_reference_songs (snapshot_version, normalized_title, normalized_artist);
CREATE INDEX idx_iktv_reference_special_tags
    ON iktv_reference_songs USING gin (special_tags);

CREATE TABLE iktv_song_match_batches (
    id BIGSERIAL PRIMARY KEY,
    batch_kind VARCHAR(16) NOT NULL CHECK (batch_kind IN ('INITIAL', 'INCREMENTAL', 'SNAPSHOT')),
    snapshot_version VARCHAR(64) NOT NULL REFERENCES iktv_reference_snapshots(snapshot_version),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'REVOKED')),
    total_count BIGINT NOT NULL DEFAULT 0,
    processed_count BIGINT NOT NULL DEFAULT 0,
    matched_count BIGINT NOT NULL DEFAULT 0,
    unmatched_count BIGINT NOT NULL DEFAULT 0,
    conflict_count BIGINT NOT NULL DEFAULT 0,
    protected_count BIGINT NOT NULL DEFAULT 0,
    out_of_scope_count BIGINT NOT NULL DEFAULT 0,
    failed_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(64)
);

CREATE UNIQUE INDEX uk_iktv_initial_batch
    ON iktv_song_match_batches (batch_kind) WHERE batch_kind = 'INITIAL';
CREATE UNIQUE INDEX uk_iktv_snapshot_batch_version
    ON iktv_song_match_batches (snapshot_version) WHERE batch_kind = 'SNAPSHOT';

CREATE TABLE iktv_song_match_work (
    song_id BIGINT PRIMARY KEY REFERENCES songs(id) ON DELETE CASCADE,
    generation BIGINT NOT NULL DEFAULT 1,
    batch_id BIGINT REFERENCES iktv_song_match_batches(id) ON DELETE SET NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RUNNING')),
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error_code VARCHAR(64)
);

CREATE INDEX idx_iktv_song_match_work_ready
    ON iktv_song_match_work (available_at, requested_at, song_id) WHERE state = 'PENDING';
CREATE INDEX idx_iktv_song_match_work_batch
    ON iktv_song_match_work (batch_id, state);

CREATE TABLE iktv_song_match_results (
    song_id BIGINT PRIMARY KEY REFERENCES songs(id) ON DELETE CASCADE,
    snapshot_version VARCHAR(64) NOT NULL REFERENCES iktv_reference_snapshots(snapshot_version),
    batch_id BIGINT REFERENCES iktv_song_match_batches(id) ON DELETE SET NULL,
    reference_song_id BIGINT,
    matching_method VARCHAR(24) NOT NULL,
    match_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    candidate_count INT NOT NULL DEFAULT 0,
    home_catalog_number VARCHAR(10) NOT NULL DEFAULT '',
    number_clues TEXT[] NOT NULL DEFAULT '{}',
    normalized_title TEXT NOT NULL DEFAULT '',
    normalized_artist TEXT NOT NULL DEFAULT '',
    catalog_number_changed BOOLEAN NOT NULL DEFAULT false,
    previous_catalog_number VARCHAR(10),
    applied_catalog_number VARCHAR(10),
    matched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_iktv_match_results_reference
    ON iktv_song_match_results (snapshot_version, reference_song_id);
CREATE INDEX idx_iktv_match_results_identity
    ON iktv_song_match_results (normalized_title, normalized_artist);
CREATE INDEX idx_iktv_match_results_number_clues
    ON iktv_song_match_results USING gin (number_clues);

CREATE TABLE iktv_song_match_batch_items (
    batch_id BIGINT NOT NULL REFERENCES iktv_song_match_batches(id) ON DELETE CASCADE,
    song_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    match_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reference_song_id BIGINT,
    catalog_number_changed BOOLEAN NOT NULL DEFAULT false,
    previous_catalog_number VARCHAR(10),
    applied_catalog_number VARCHAR(10),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch_id, song_id)
);

-- Capture only committed database changes. These triggers do not access the
-- filesystem and do not write to source media. The worker marks its own
-- catalog-number fill with a transaction-local flag to prevent self-enqueue.
CREATE OR REPLACE FUNCTION enqueue_iktv_song_match(p_song_id BIGINT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF p_song_id IS NULL OR current_setting('homektv.reference_fusion', true) = 'on' THEN
        RETURN;
    END IF;

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

CREATE OR REPLACE FUNCTION trg_enqueue_iktv_song_match()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM enqueue_iktv_song_match(NEW.id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER songs_enqueue_iktv_match_insert
    AFTER INSERT ON songs FOR EACH ROW EXECUTE FUNCTION trg_enqueue_iktv_song_match();
CREATE TRIGGER songs_enqueue_iktv_match_identity_update
    AFTER UPDATE OF title, artist, catalog_number, metadata_locks ON songs
    FOR EACH ROW
    WHEN (ROW(OLD.title, OLD.artist, OLD.catalog_number, OLD.metadata_locks)
          IS DISTINCT FROM ROW(NEW.title, NEW.artist, NEW.catalog_number, NEW.metadata_locks))
    EXECUTE FUNCTION trg_enqueue_iktv_song_match();

CREATE OR REPLACE FUNCTION trg_enqueue_iktv_song_file_match()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM enqueue_iktv_song_match(OLD.song_id);
        RETURN OLD;
    END IF;

    PERFORM enqueue_iktv_song_match(NEW.song_id);
    IF TG_OP = 'UPDATE' AND OLD.song_id IS DISTINCT FROM NEW.song_id THEN
        PERFORM enqueue_iktv_song_match(OLD.song_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER song_files_enqueue_iktv_match_insert_delete
    AFTER INSERT OR DELETE ON song_files FOR EACH ROW
    EXECUTE FUNCTION trg_enqueue_iktv_song_file_match();
CREATE TRIGGER song_files_enqueue_iktv_match_identity_update
    AFTER UPDATE OF song_id, file_path, relative_path, valid, file_role, source_deleted ON song_files
    FOR EACH ROW
    WHEN (ROW(OLD.song_id, OLD.file_path, OLD.relative_path, OLD.valid, OLD.file_role, OLD.source_deleted)
          IS DISTINCT FROM ROW(NEW.song_id, NEW.file_path, NEW.relative_path, NEW.valid, NEW.file_role, NEW.source_deleted))
    EXECUTE FUNCTION trg_enqueue_iktv_song_file_match();

CREATE OR REPLACE FUNCTION trg_record_iktv_song_match_out_of_scope()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    pending_batch_id BIGINT;
BEGIN
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

CREATE TRIGGER songs_record_iktv_match_out_of_scope
    BEFORE DELETE ON songs FOR EACH ROW
    EXECUTE FUNCTION trg_record_iktv_song_match_out_of_scope();
