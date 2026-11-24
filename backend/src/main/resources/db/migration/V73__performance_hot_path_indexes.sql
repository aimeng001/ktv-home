-- Performance hot-path indexes (Phase 1 & Phase 2):
-- 1. Support fast catalog_number exact lookup without scanning all songs.
CREATE INDEX IF NOT EXISTS idx_songs_ok_catalog_number
    ON songs (catalog_number)
    WHERE status = 'ok' AND catalog_number <> '';

-- 2. Support millisecond word-count browse (1..5 chars and 6+ chars) ordered by popularity.
CREATE INDEX IF NOT EXISTS idx_songs_ok_title_len_play
    ON songs (LEAST(char_length(title), 6), play_count DESC, id ASC)
    WHERE status = 'ok';

-- 3. Speed up ready-song catalog statistics and active-role readiness scans.
CREATE INDEX IF NOT EXISTS idx_song_files_active_ready
    ON song_files (file_role, song_id)
    WHERE valid = TRUE AND probe_pending = FALSE;
