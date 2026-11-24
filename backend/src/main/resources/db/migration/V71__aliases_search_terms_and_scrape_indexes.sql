-- Index song aliases into song_search_terms and support bounded suggested-match purge.

CREATE INDEX IF NOT EXISTS idx_songs_title_lower
    ON songs (lower(title));

CREATE INDEX IF NOT EXISTS idx_song_external_matches_suggested_purge
    ON song_external_matches (updated_at, id)
    WHERE status = 'SUGGESTED';

CREATE OR REPLACE FUNCTION ktv_rebuild_song_search_terms(p_song_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $ktv$
DECLARE
    song_row RECORD;
    tag TEXT;
    alias_val TEXT;
BEGIN
    EXECUTE 'DELETE FROM song_search_terms
             WHERE song_id = $1
               AND kind IN (
                   ''TITLE'', ''ARTIST'', ''LANGUAGE'', ''TAG'', ''ALIAS'',
                   ''TITLE_PY'', ''TITLE_INIT'', ''ARTIST_PY'', ''ARTIST_INIT''
               )'
        USING p_song_id;

    SELECT title, artist, title_py, title_init, artist_py,
           artist_init, language, tags, aliases
    INTO song_row
    FROM songs
    WHERE id = p_song_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    PERFORM ktv_insert_short_search_terms(p_song_id, 'TITLE', song_row.title);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'TITLE_PY', song_row.title_py);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'TITLE_INIT', song_row.title_init);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'ARTIST', song_row.artist);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'ARTIST_PY', song_row.artist_py);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'ARTIST_INIT', song_row.artist_init);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'LANGUAGE', song_row.language);
    FOREACH tag IN ARRAY COALESCE(song_row.tags, ARRAY[]::TEXT[]) LOOP
        PERFORM ktv_insert_short_search_terms(p_song_id, 'TAG', tag);
    END LOOP;
    FOREACH alias_val IN ARRAY COALESCE(song_row.aliases, ARRAY[]::TEXT[]) LOOP
        IF COALESCE(trim(alias_val), '') <> '' THEN
            INSERT INTO song_search_terms (song_id, kind, value, value_lower)
            VALUES (p_song_id, 'ALIAS', trim(alias_val), lower(trim(alias_val)))
            ON CONFLICT DO NOTHING;
            PERFORM ktv_insert_short_search_terms(p_song_id, 'ALIAS', trim(alias_val));
        END IF;
    END LOOP;
END
$ktv$;

DROP TRIGGER IF EXISTS trg_songs_search_terms_update ON songs;

CREATE TRIGGER trg_songs_search_terms_update
AFTER UPDATE OF title, artist, title_py, title_init, artist_py, artist_init,
    language, tags, aliases ON songs
FOR EACH ROW
WHEN (OLD.title IS DISTINCT FROM NEW.title
   OR OLD.artist IS DISTINCT FROM NEW.artist
   OR OLD.title_py IS DISTINCT FROM NEW.title_py
   OR OLD.title_init IS DISTINCT FROM NEW.title_init
   OR OLD.artist_py IS DISTINCT FROM NEW.artist_py
   OR OLD.artist_init IS DISTINCT FROM NEW.artist_init
   OR OLD.language IS DISTINCT FROM NEW.language
   OR OLD.tags IS DISTINCT FROM NEW.tags
   OR OLD.aliases IS DISTINCT FROM NEW.aliases)
EXECUTE FUNCTION ktv_sync_song_search_terms();

CREATE INDEX IF NOT EXISTS idx_song_search_terms_alias_lower_trgm
    ON song_search_terms USING gin (value_lower gin_trgm_ops)
    WHERE kind = 'ALIAS';

INSERT INTO song_search_terms (song_id, kind, value, value_lower)
SELECT songs.id, 'ALIAS', trim(aliases.value), lower(trim(aliases.value))
FROM songs
CROSS JOIN LATERAL unnest(COALESCE(songs.aliases, ARRAY[]::TEXT[])) AS aliases(value)
WHERE trim(aliases.value) <> ''
ON CONFLICT DO NOTHING;
