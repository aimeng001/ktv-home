-- Add a platform-neutral audio layout without rewriting media files.
ALTER TABLE song_files
    ADD COLUMN IF NOT EXISTS audio_layout TEXT NOT NULL DEFAULT 'NORMAL_STEREO',
    ADD COLUMN IF NOT EXISTS original_track_index INT,
    ADD COLUMN IF NOT EXISTS accompaniment_track_index INT,
    ADD COLUMN IF NOT EXISTS original_channel TEXT NOT NULL DEFAULT 'LEFT',
    ADD COLUMN IF NOT EXISTS accompaniment_channel TEXT NOT NULL DEFAULT 'RIGHT';

-- Preserve the existing Home KTV two-track meaning for legacy rows.
UPDATE song_files
SET audio_layout = 'DUAL_TRACK',
    vocal_track_index = COALESCE(vocal_track_index, 1),
    accompaniment_track_index = COALESCE(vocal_track_index, 1),
    original_track_index = CASE WHEN COALESCE(vocal_track_index, 1) = 0 THEN 1 ELSE 0 END
WHERE audio_tracks >= 2
  AND audio_layout = 'NORMAL_STEREO';

-- Keep the old column and the new semantic alias consistent for rows that were
-- created by an older application version or a partial migration.
UPDATE song_files
SET accompaniment_track_index = vocal_track_index
WHERE accompaniment_track_index IS NULL
  AND vocal_track_index IS NOT NULL;

UPDATE song_files
SET original_track_index = CASE WHEN accompaniment_track_index = 0 THEN 1 ELSE 0 END
WHERE audio_layout = 'DUAL_TRACK'
  AND original_track_index IS NULL
  AND accompaniment_track_index IS NOT NULL;
