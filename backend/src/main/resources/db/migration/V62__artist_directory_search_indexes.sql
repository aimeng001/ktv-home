-- Support millisecond artist directory keyword / pinyin / initials search on artist_directory_stats
CREATE INDEX IF NOT EXISTS idx_artist_directory_init_lower_prefix
    ON artist_directory_stats (LOWER(artist_init) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_artist_directory_py_lower_prefix
    ON artist_directory_stats (LOWER(artist_py) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_artist_directory_name_trgm
    ON artist_directory_stats USING gin (LOWER(name) gin_trgm_ops);
