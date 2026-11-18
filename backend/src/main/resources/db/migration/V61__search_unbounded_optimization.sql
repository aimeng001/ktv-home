-- Partial functional prefix indexes over active ('ok') songs so unbounded
-- paginated searches and short-acronym queries resolve via compact index scans.
CREATE INDEX IF NOT EXISTS idx_songs_ok_title_lower_prefix
    ON songs (lower(title) text_pattern_ops)
    WHERE status = 'ok';

CREATE INDEX IF NOT EXISTS idx_songs_ok_title_init_lower_prefix
    ON songs (lower(title_init) text_pattern_ops)
    WHERE status = 'ok';

CREATE INDEX IF NOT EXISTS idx_songs_ok_title_py_lower_prefix
    ON songs (lower(title_py) text_pattern_ops)
    WHERE status = 'ok';

CREATE INDEX IF NOT EXISTS idx_songs_ok_artist_lower_prefix
    ON songs (lower(artist) text_pattern_ops)
    WHERE status = 'ok';

CREATE INDEX IF NOT EXISTS idx_songs_ok_artist_init_lower_prefix
    ON songs (lower(artist_init) text_pattern_ops)
    WHERE status = 'ok';

CREATE INDEX IF NOT EXISTS idx_songs_ok_artist_py_lower_prefix
    ON songs (lower(artist_py) text_pattern_ops)
    WHERE status = 'ok';
