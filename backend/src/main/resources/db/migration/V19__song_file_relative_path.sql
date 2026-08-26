ALTER TABLE song_files
    ADD COLUMN IF NOT EXISTS relative_path TEXT;
