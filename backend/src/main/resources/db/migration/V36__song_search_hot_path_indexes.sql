-- V36__song_search_hot_path_indexes.sql
-- Indexes for hot-path prefix matching and candidate filtering in song search.

-- 1. Pinyin and initials prefix indexes on songs (text_pattern_ops for LIKE 'xxx%')
CREATE INDEX IF NOT EXISTS idx_songs_title_py_prefix
    ON songs (title_py text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_songs_title_init_prefix
    ON songs (title_init text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_songs_artist_py_prefix
    ON songs (artist_py text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_songs_artist_init_prefix
    ON songs (artist_init text_pattern_ops);

-- 2. Song artists pinyin and initials prefix indexes (for credited artists in duets/features)
CREATE INDEX IF NOT EXISTS idx_song_artists_artist_py_prefix
    ON song_artists (artist_py text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_song_artists_artist_init_prefix
    ON song_artists (artist_init text_pattern_ops);

-- 3. Language prefix index (supports '粤' -> '粤语'). The query lower-cases
-- language values so mixed-case legacy rows remain searchable as well.
CREATE INDEX IF NOT EXISTS idx_songs_language_prefix
    ON songs (language text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_songs_language_lower_prefix
    ON songs (lower(language) text_pattern_ops);

-- 4. Keep the array GIN index available for exact-tag maintenance queries.
-- The public search contract currently supports case-insensitive tag
-- substrings, which is validated separately before choosing an indexed term
-- table or changing the operator.
CREATE INDEX IF NOT EXISTS idx_songs_tags_gin
    ON songs USING gin (tags);

-- 5. Status and media_type composite index for candidate filtering
CREATE INDEX IF NOT EXISTS idx_songs_status_media_type
    ON songs (status, media_type);
