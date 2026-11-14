CREATE INDEX IF NOT EXISTS idx_song_artists_key_lower
    ON song_artists (LOWER(artist_key), song_id);

CREATE INDEX IF NOT EXISTS idx_songs_ok_norm_artist
    ON songs (LOWER(regexp_replace(trim(artist), '[[:space:]]+', '', 'g')))
    WHERE status = 'ok';

CREATE INDEX IF NOT EXISTS idx_songs_ok_play_title_id
    ON songs (play_count DESC, title ASC, id ASC)
    WHERE status = 'ok';

CREATE INDEX IF NOT EXISTS idx_songs_ok_language_lower
    ON songs (LOWER(language), play_count DESC, title ASC, id ASC)
    WHERE status = 'ok';
