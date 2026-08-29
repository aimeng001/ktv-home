ALTER TABLE song_files
    ADD COLUMN IF NOT EXISTS media_type TEXT,
    ADD COLUMN IF NOT EXISTS audio_layout_source TEXT NOT NULL DEFAULT 'LEGACY';

-- Existing Song.media_type is the only available probe result for legacy
-- rows.  Use it as a safe per-file baseline until that file is probed again.
UPDATE song_files sf
SET media_type = s.media_type
FROM songs s
WHERE sf.song_id = s.id
  AND sf.media_type IS NULL;
