-- Keep the high-hit one/two-character path index-only for the term projection.
-- The source media files remain outside the database and are never modified.
CREATE INDEX IF NOT EXISTS idx_song_search_terms_short_covering
    ON song_search_terms (value_lower, song_id) INCLUDE (kind);