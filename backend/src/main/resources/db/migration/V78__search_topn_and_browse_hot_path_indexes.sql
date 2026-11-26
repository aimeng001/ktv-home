-- V78: Top-N partial indexes for 2-char prefix/CJK first-char search and language browsing.
-- Append-only non-destructive DDL (MigrationSafetyTest compliant).

-- 1. Two-letter ASCII pinyin/initial prefix hot Top-N indexes ('yq', 'zh', ...)
--    so 2-letter queries return highest play_count songs directly without arbitrary truncation.
CREATE INDEX IF NOT EXISTS idx_songs_ok_title_init_prefix2_play
    ON songs (LOWER(left(title_init, 2)), play_count DESC, id ASC)
    WHERE status = 'ok' AND char_length(title_init) >= 2;

CREATE INDEX IF NOT EXISTS idx_songs_ok_artist_init_prefix2_play
    ON songs (LOWER(left(artist_init, 2)), play_count DESC, id ASC)
    WHERE status = 'ok' AND char_length(artist_init) >= 2;

-- 2. First-character CJK/title/artist Top-N indexes for 1-2 character prefix queries.
CREATE INDEX IF NOT EXISTS idx_songs_ok_title_first_play
    ON songs (LOWER(left(title, 1)), play_count DESC, id ASC)
    WHERE status = 'ok' AND title <> '';

CREATE INDEX IF NOT EXISTS idx_songs_ok_artist_first_play
    ON songs (LOWER(left(artist, 1)), play_count DESC, id ASC)
    WHERE status = 'ok' AND artist <> '';

-- 3. Case-insensitive language + play_count hot browse index.
CREATE INDEX IF NOT EXISTS idx_songs_ok_language_lower_play
    ON songs (LOWER(language), play_count DESC, id ASC)
    WHERE status = 'ok' AND language <> '';
