ALTER TABLE song_files
    ADD COLUMN IF NOT EXISTS file_identity TEXT,
    ADD COLUMN IF NOT EXISTS probe_pending BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE media_import_records
    ADD COLUMN IF NOT EXISTS source_size BIGINT,
    ADD COLUMN IF NOT EXISTS source_mtime TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS source_file_identity TEXT;
