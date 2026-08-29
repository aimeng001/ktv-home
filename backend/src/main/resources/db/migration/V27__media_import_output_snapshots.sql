ALTER TABLE media_import_records
    ADD COLUMN IF NOT EXISTS output_size BIGINT,
    ADD COLUMN IF NOT EXISTS output_mtime TIMESTAMPTZ;
