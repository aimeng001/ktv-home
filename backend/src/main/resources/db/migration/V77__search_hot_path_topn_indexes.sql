-- V77: Hot-path Top-N and lower-case indexes for multi-shape song search & admin library filtering.
-- Append-only non-destructive DDL (MigrationSafetyTest compliant).

-- 1. Single-initial hottest Top-N partial indexes so 1-letter queries ('y', 'z', ...)
--    emit top play_count rows directly from the index without sorting 20,000+ rows.
CREATE INDEX IF NOT EXISTS idx_songs_ok_title_init_first_play
    ON songs (LOWER(left(title_init, 1)), play_count DESC, id ASC)
    WHERE status = 'ok' AND title_init <> '';

CREATE INDEX IF NOT EXISTS idx_songs_ok_title_init_first_media_play
    ON songs (LOWER(left(title_init, 1)), media_type, play_count DESC, id ASC)
    WHERE status = 'ok' AND title_init <> '';

CREATE INDEX IF NOT EXISTS idx_songs_ok_artist_init_first_play
    ON songs (LOWER(left(artist_init, 1)), play_count DESC, id ASC)
    WHERE status = 'ok' AND artist_init <> '';

CREATE INDEX IF NOT EXISTS idx_songs_ok_artist_init_first_media_play
    ON songs (LOWER(left(artist_init, 1)), media_type, play_count DESC, id ASC)
    WHERE status = 'ok' AND artist_init <> '';

-- 2. Collaborative artist lower-case trigram + single-initial index.
CREATE INDEX IF NOT EXISTS idx_song_artists_name_lower_trgm
    ON song_artists USING gin (LOWER(artist_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_song_artists_init_first
    ON song_artists (LOWER(left(artist_init, 1)), song_id)
    WHERE artist_init <> '';

-- 3. Fast valid-file existence check for AdminSongQueryRepository.
CREATE INDEX IF NOT EXISTS idx_song_files_valid_song_id
    ON song_files (song_id)
    WHERE valid = TRUE;
