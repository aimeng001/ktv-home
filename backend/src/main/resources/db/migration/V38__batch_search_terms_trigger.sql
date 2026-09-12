-- Replace the row-level songs INSERT trigger from V37 with a statement-level
-- transition-table trigger. Large imports must not execute one delete and
-- several insert statements per song. Relevant UPDATEs stay row-scoped so
-- play-count and other unrelated writes do not rebuild the projection.
CREATE OR REPLACE FUNCTION ktv_sync_song_search_terms_statement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $ktv$
BEGIN
    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT rows.id, sources.kind, grams.value, lower(grams.value)
    FROM new_rows rows
    CROSS JOIN LATERAL (
        SELECT 'TITLE'::VARCHAR(32) AS kind, COALESCE(rows.title, '') AS source_value
        UNION ALL SELECT 'TITLE_PY', COALESCE(rows.title_py, '')
        UNION ALL SELECT 'TITLE_INIT', COALESCE(rows.title_init, '')
        UNION ALL SELECT 'ARTIST', COALESCE(rows.artist, '')
        UNION ALL SELECT 'ARTIST_PY', COALESCE(rows.artist_py, '')
        UNION ALL SELECT 'ARTIST_INIT', COALESCE(rows.artist_init, '')
        UNION ALL SELECT 'LANGUAGE', COALESCE(rows.language, '')
        UNION ALL
        SELECT 'TAG', tag
        FROM unnest(COALESCE(rows.tags, ARRAY[]::TEXT[])) AS tags(tag)
    ) AS sources
    CROSS JOIN LATERAL generate_series(1, char_length(sources.source_value)) AS positions(position)
    CROSS JOIN (VALUES (1), (2)) AS lengths(length)
    CROSS JOIN LATERAL (
        SELECT substring(sources.source_value FROM positions.position FOR lengths.length) AS value
    ) AS grams
    WHERE grams.value <> ''
      AND positions.position + lengths.length - 1 <= char_length(sources.source_value)
    ON CONFLICT DO NOTHING;

    RETURN NULL;
END
$ktv$;

DROP TRIGGER IF EXISTS trg_songs_search_terms ON songs;

CREATE TRIGGER trg_songs_search_terms_insert
AFTER INSERT ON songs
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION ktv_sync_song_search_terms_statement();

CREATE TRIGGER trg_songs_search_terms_update
AFTER UPDATE OF title, artist, language, tags ON songs
FOR EACH ROW
WHEN (OLD.title IS DISTINCT FROM NEW.title
   OR OLD.artist IS DISTINCT FROM NEW.artist
   OR OLD.language IS DISTINCT FROM NEW.language
   OR OLD.tags IS DISTINCT FROM NEW.tags)
EXECUTE FUNCTION ktv_sync_song_search_terms();
