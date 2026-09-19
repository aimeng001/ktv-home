ALTER TABLE library_scan_state
    ADD COLUMN IF NOT EXISTS root_identity VARCHAR(512);

-- Existing V45 rows have the canonical path but no filesystem token. Backfill
-- that legacy value so the first post-migration claim can upgrade it safely.
UPDATE library_scan_state
   SET root_identity = configured_root
 WHERE root_identity IS NULL;

CREATE TABLE IF NOT EXISTS library_catalog_revision (
    library_key VARCHAR(128) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

